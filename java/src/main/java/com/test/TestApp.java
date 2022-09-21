package com.test;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.DownScopedCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.DownScopedCredentials.DownScopedOptions;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

public class TestApp {
     public static void main(String[] args) {
          try {
               TestApp tc = new TestApp();
          } catch (Exception ex) {
               System.out.println(ex);
          }
     }

     public TestApp() throws Exception {
          try {

               String bucketName = "yourbucket";
               String keyFile = "/path/to/svc_account.json";

               String projectId = ServiceOptions.getDefaultProjectId();
               System.out.println(projectId);

               GoogleCredentials sourceCredentials;

               // File credentialsPath = new File(keyFile);
               // try (FileInputStream serviceAccountStream = new FileInputStream(credentialsPath)) {
               //      sourceCredentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
               // }
               // if (sourceCredentials.createScopedRequired())
               //      sourceCredentials = sourceCredentials
               //                .createScoped("https://www.googleapis.com/auth/cloud-platform");

               sourceCredentials = GoogleCredentials.getApplicationDefault();

               DownScopedCredentials.AvailabilityCondition ap = new DownScopedCredentials.AvailabilityCondition();
               ap.setTitle("obj");
               ap.setExpression("resource.name.startsWith(\"projects/_/buckets/" + bucketName + "/objects/foo.txt\")");

               DownScopedCredentials.AccessBoundaryRule abr = new DownScopedCredentials.AccessBoundaryRule();
               abr.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucketName);
               abr.addAvailablePermission("inRole:roles/storage.objectViewer");
               abr.setAvailabilityCondition(ap);

               DownScopedCredentials.AccessBoundary ab = new DownScopedCredentials.AccessBoundary();
               ab.setAccessBoundaryRules(abr);

               DownScopedOptions dopt = new DownScopedOptions();
               dopt.setAccessBoundary(ab);
   
               DownScopedCredentials dc = DownScopedCredentials.create(sourceCredentials, dopt);

               // Normally, you give the token back directly to a client to use
               // In the following, the AccessToken's value is used to generate a new
               // GoogleCredential object at the client:
               // AccessToken tok = dc.refreshAccessToken();
               // System.out.println(tok.getTokenValue());
               // GoogleCredentials sts = GoogleCredentials.create(tok);

               Storage storage = StorageOptions.newBuilder().setCredentials(dc).build().getService();
               Blob blob = storage.get(bucketName, "foo.txt");
               String fileContent = new String(blob.getContent());

               System.out.println(fileContent);


          } catch (Exception ex) {
               System.out.println("Error:  " + ex.getMessage());
          }
     }

}
