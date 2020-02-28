/*
 * Copyright 2020, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.google.auth.oauth2;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.util.GenericData;
import com.google.auth.http.HttpTransportFactory;
import com.google.common.base.MoreObjects;
import com.google.gson.Gson;

/**
 * DownScoped credentials allows for exchanging a parent Credential's
 * access_token for another access_token that has permissions on a limited set
 * of resources the parent token originally had.
 *
 * For example, if the parent Credential that represents user Alice has access
 * to GCS buckets A, B, C, you can exchange the Alice's credential for another
 * credential that still identifies Alice but can only be used against Bucket A
 * and C.
 *
 * DownScoped tokens currently only works for GCS resources.
 *
 * ** NOTE:** DownScoped tokens currently only works for GCS buckets and cannot
 * be applied (yet) at the bucket+path or object level. The GCS bucket must be
 * enabled with [Uniform bucket-level
 * access](https://cloud.google.com/storage/docs/uniform-bucket-level-access
 *
 * DownScoped tokens are normally used in a tokenbroker/exchange service where
 * you can mint a new restricted token to hand to a client. The sample below
 * shows how to generate a downscoped token, extract the raw access_token, and
 * then inject the raw token in another TokenSource (instead of just using the
 * DownScopedToken as the TokenSource directly in the storageClient.).
 *
 *
 * The following shows how to exchange a root credential for a downscoped
 * credential that can only be used as roles/storage.objectViewer against GCS
 * bucketA.
 *
 *
 * <pre>
 * String bucketName = "bucketA";
 *
 * GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
 *
 * List<DownScopedCredentials.AccessBoundaryRule> alist = new ArrayList<DownScopedCredentials.AccessBoundaryRule>();
 *
 * DownScopedCredentials.AccessBoundaryRule ab = new DownScopedCredentials.AccessBoundaryRule();
 * ab.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucketName);
 * ab.addAvailablePermission("inRole:roles/storage.objectViewer");
 * alist.add(ab);
 *
 * DownScopedCredentials dc = DownScopedCredentials.create(sourceCredentials, alist);
 *
 * // AccessToken tok = dc.refreshAccessToken();
 * // System.out.println(tok.getTokenValue());
 *
 * Storage storage = StorageOptions.newBuilder().setCredentials(dc).build().getService();
 * Page<Blob> blobs = storage.list(bucketName);
 * for (Blob blob : blobs.iterateAll()) {
 *   System.out.println(blob.getName());
 * }
 * </pre>
 */

public class DownScopedCredentials extends GoogleCredentials {

  public static class AccessBoundaryRule {

    private String availableResource;
    private List<String> availablePermissions = new ArrayList<String>();

    public void setAvailableResource(String ar) {
      this.availableResource = ar;
    }

    public void addAvailablePermission(String ar) {
      this.availablePermissions.add(ar);
    }

    public String getAvailableResource() {
      return this.availableResource;
    }

    public List<String> getAvailablePermissions() {
      return this.availablePermissions;
    }
  }

  private static final long serialVersionUID = -2133257318957488431L;
  private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
  private static final String IDENTITY_TOKEN_ENDPOINT = "https://securetoken.googleapis.com/v1alpha2/identitybindingtoken";
  //private static final String IDENTITY_TOKEN_ENDPOINT = "https://securetoken.googleapis.com/v2beta1/token";
  
  private static final String TOKEN_INFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/tokeninfo";

  private GoogleCredentials sourceCredentials;
  private List<AccessBoundaryRule> accessBoundaryRules;
  private final String transportFactoryClassName;
  private transient HttpTransportFactory transportFactory;

  public static DownScopedCredentials create(GoogleCredentials sourceCredentials,
      List<AccessBoundaryRule> accessBoundaryRules) {
    return DownScopedCredentials.newBuilder().setSourceCredentials(sourceCredentials)
        .setAccessBoundaryRules(accessBoundaryRules).build();
  }

  public static DownScopedCredentials create(GoogleCredentials sourceCredentials,
      List<AccessBoundaryRule> accessBoundaryRules, HttpTransportFactory transportFactory) {
    return DownScopedCredentials.newBuilder().setSourceCredentials(sourceCredentials)
        .setAccessBoundaryRules(accessBoundaryRules).setHttpTransportFactory(transportFactory).build();
  }

  private DownScopedCredentials(Builder builder) {
    this.sourceCredentials = builder.getSourceCredentials();
    this.accessBoundaryRules = builder.getAccessBoundaryRules();
    this.transportFactory = firstNonNull(builder.getHttpTransportFactory(),
        getFromServiceLoader(HttpTransportFactory.class, OAuth2Utils.HTTP_TRANSPORT_FACTORY));
    this.transportFactoryClassName = this.transportFactory.getClass().getName();
  }

  @Override
  public AccessToken refreshAccessToken() throws IOException {

    if (this.sourceCredentials.createScopedRequired()) {
      this.sourceCredentials = this.sourceCredentials.createScoped(Arrays.asList(CLOUD_PLATFORM_SCOPE));
    }

    try {
      this.sourceCredentials.refreshIfExpired();
    } catch (IOException e) {
      throw new IOException("Unable to refresh sourceCredentials", e);
    }

    AccessToken tok = this.sourceCredentials.getAccessToken();

    HttpTransport httpTransport = this.transportFactory.create();
    JsonObjectParser parser = new JsonObjectParser(OAuth2Utils.JSON_FACTORY);

    HttpRequestFactory requestFactory = httpTransport.createRequestFactory();
    GenericUrl url = new GenericUrl(IDENTITY_TOKEN_ENDPOINT);

    Map<String, List<DownScopedCredentials.AccessBoundaryRule>> r = new HashMap<String, List<DownScopedCredentials.AccessBoundaryRule>>();
    r.put("accessBoundaryRules", this.accessBoundaryRules);

    String jsonPayload;
    Gson gson = new Gson();
    jsonPayload = gson.toJson(r);

    // ObjectMapper objectMapper = new ObjectMapper();
    // jsonPayload = objectMapper.writeValueAsString(r);
    // System.out.println("Using com.fasterxml.jackson.databind.ObjectMapper : " +
    // jsonPayload);

    Map<String, String> params = new HashMap<>();
    params.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
    params.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
    params.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
    params.put("subject_token", tok.getTokenValue());
    params.put("access_boundary", jsonPayload);
    HttpContent content = new UrlEncodedContent(params);

    HttpRequest request = requestFactory.buildPostRequest(url, content);
    request.setParser(parser);

    HttpResponse response = null;
    try {
      response = request.execute();
    } catch (IOException e) {
      throw new IOException("Error requesting access token " + e.getMessage(), e);
    }

    if (response.getStatusCode() != HttpStatusCodes.STATUS_CODE_OK) {
      throw new IOException("Error getting access token " + response.toString());
    }

    GenericData responseData = response.parseAs(GenericData.class);
    response.disconnect();

    String access_token = (String) responseData.get("access_token");

    Date now = new Date();
    Calendar cal = Calendar.getInstance();
    cal.setTime(now);

    // an exchanged token that is derived from a service account (2LO) has an
    // expired_in value a token derived from a users token (3LO) does not.
    // The following code uses the time remaining on rootToken for a user as the
    // value for the derived token's lifetime
    if (responseData.containsKey("expires_in")) {
      cal.add(Calendar.SECOND, ((BigDecimal) responseData.get("expires_in")).intValue());
    } else {
      GenericUrl genericUrl = new GenericUrl(TOKEN_INFO_ENDPOINT);
      genericUrl.put("access_token", tok.getTokenValue());
      HttpRequest tokenRequest = requestFactory.buildGetRequest(genericUrl);
      tokenRequest.setParser(parser);
      HttpResponse tokenResponse = tokenRequest.execute();
      if (tokenResponse.getStatusCode() != HttpStatusCodes.STATUS_CODE_OK) {
        throw new IOException("Error getting access_token expiration " + response.toString());
      }
      responseData = tokenResponse.parseAs(GenericData.class);
      tokenResponse.disconnect();
      cal.add(Calendar.SECOND, Integer.parseInt(responseData.get("expires_in").toString()));
    }
    return new AccessToken(access_token, cal.getTime());
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceCredentials, accessBoundaryRules);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("sourceCredentials", sourceCredentials)
        .add("accessBoundaryRules", accessBoundaryRules).add("transportFactoryClassName", transportFactoryClassName)
        .toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof DownScopedCredentials)) {
      return false;
    }
    DownScopedCredentials other = (DownScopedCredentials) obj;
    return Objects.equals(this.sourceCredentials, other.sourceCredentials)
        && Objects.equals(this.accessBoundaryRules, other.accessBoundaryRules)
        && Objects.equals(this.transportFactoryClassName, other.transportFactoryClassName);
  }

  public Builder toBuilder() {
    return new Builder(this.sourceCredentials, this.accessBoundaryRules);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder extends GoogleCredentials.Builder {

    private GoogleCredentials sourceCredentials;
    private List<AccessBoundaryRule> accessBoundaryRules;
    private HttpTransportFactory transportFactory;

    protected Builder() {
    }

    protected Builder(GoogleCredentials sourceCredentials, List<AccessBoundaryRule> accessBoundaryRules) {
      this.sourceCredentials = sourceCredentials;
      this.accessBoundaryRules = accessBoundaryRules;
    }

    public Builder setSourceCredentials(GoogleCredentials sourceCredentials) {
      this.sourceCredentials = sourceCredentials;
      return this;
    }

    public GoogleCredentials getSourceCredentials() {
      return this.sourceCredentials;
    }

    public Builder setAccessBoundaryRules(List<AccessBoundaryRule> accessBoundaryRules) {
      this.accessBoundaryRules = accessBoundaryRules;
      return this;
    }

    public List<AccessBoundaryRule> getAccessBoundaryRules() {
      return this.accessBoundaryRules;
    }

    public Builder setHttpTransportFactory(HttpTransportFactory transportFactory) {
      this.transportFactory = transportFactory;
      return this;
    }

    public HttpTransportFactory getHttpTransportFactory() {
      return transportFactory;
    }

    public DownScopedCredentials build() {
      return new DownScopedCredentials(this);
    }
  }
}
