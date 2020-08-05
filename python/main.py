import google.auth
from google.oauth2 import service_account
from google.cloud import storage

from google.auth import downscoped_credentials

bucket_name = "your-bucket"

svcAccountFile = "/path/to/svc_account.json"
target_scopes = [
    'https://www.googleapis.com/auth/devstorage.read_only']

# source_credentials = (
#     service_account.Credentials.from_service_account_file(
#         svcAccountFile,
#         scopes=target_scopes))

source_credentials, project_id = google.auth.default()        

downscoped_options = {
  "accessBoundary" : {
      "accessBoundaryRules" : [
        {
          "availableResource" : "//storage.googleapis.com/projects/_/buckets/" + bucket_name,
          "availablePermissions": ["inRole:roles/storage.objectViewer"],
          "availabilityCondition" : {
            "title" : "obj-prefixes",
            "expression" : "resource.name.startsWith(\"projects/_/buckets/" + bucket_name + "/objects/foo.txt\")"
          }
        }
      ]
  }
}


# or use default credentials
source_credentials, project_id = google.auth.default()

dc = downscoped_credentials.Credentials(source_credentials=source_credentials,downscoped_options=downscoped_options)

storage_client = storage.Client(credentials=dc)
bucket = storage_client.bucket(bucket_name)
blob = bucket.blob("foo.txt")
print(blob.download_as_string())
