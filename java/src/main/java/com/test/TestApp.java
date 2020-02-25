package com.test;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.DownScopedCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
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

               String bucketName = "mineral-minutia-820-bucket";
               String keyFile = "/home/srashid/gcp_misc/certs/mineral-minutia-820-83b3ce7dcddb.json";

               String projectId = ServiceOptions.getDefaultProjectId();
               System.out.println(projectId);

               GoogleCredentials sourceCredentials;

               File credentialsPath = new File(keyFile);
               try (FileInputStream serviceAccountStream = new FileInputStream(credentialsPath)) {
                    sourceCredentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
               }
               if (sourceCredentials.createScopedRequired())
                    sourceCredentials = sourceCredentials
                              .createScoped("https://www.googleapis.com/auth/cloud-platform");

               // sourceCredentials = GoogleCredentials.getApplicationDefault();

               List<DownScopedCredentials.AccessBoundaryRule> alist = new ArrayList<DownScopedCredentials.AccessBoundaryRule>();
               DownScopedCredentials.AccessBoundaryRule ab = new DownScopedCredentials.AccessBoundaryRule();
               ab.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucketName);
               ab.addAvailablePermission("inRole:roles/storage.objectViewer");
               alist.add(ab);

               DownScopedCredentials dc = DownScopedCredentials.create(sourceCredentials, alist);

               // Normally, you give the token back directly to a client to use
               // In the following, the AccessToken's value is used to generate a new
               // GoogleCredential object at the client:
               // AccessToken tok = dc.refreshAccessToken();
               // System.out.println(tok.getTokenValue());
               // GoogleCredentials sts = GoogleCredentials.create(tok);

               Storage storage = StorageOptions.newBuilder().setCredentials(dc).build().getService();
               Page<Blob> blobs = storage.list(bucketName);
               for (Blob blob : blobs.iterateAll()) {
                    System.out.println(blob.getName());
               }

          } catch (Exception ex) {
               System.out.println("Error:  " + ex.getMessage());
          }
     }

}
