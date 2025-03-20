## Using Credential Access Boundary (DownScoped) Tokens

`Credential Access Boundary` is a policy language that you can use to downscope the accessing power of your GCP short-lived credentials. You can define a Credential Access Boundary that specifies which resources the short-lived credential can access, as well as an upper bound on the permissions that are available on each resource

For example, if the parent Credential that represents user Alice has Read/Write access to GCS buckets `A`, `B`, `C`, you can exchange the Alice's credential for another credential that still identifies Alice but can only be used for Read against Bucket `A` and `C`.   You can also define an expression based on a partial resource path and prefix (eg, downscope a token with permissions on a specific object in GCS or a path within GCS) 


DownScoped tokens are normally used in a tokenbroker/exchange service where you can mint a new restricted token to hand to a client. The sample usage and implementations below shows how to generate a downscoped token, extract the raw access_token, and then inject the raw token in another TokenSource.

** NOTE:**

* DownScoped tokens currently only works for certain resources like GCS, GCE and Cloud Resource Manager objects like Projects, Folders and Organizations.
* The GCS bucket must be enabled with [Uniform bucket-level access](https://cloud.google.com/storage/docs/uniform-bucket-level-access


>>> Important, before you go down the road of using downscoped token and token brokers, consider the usecase you're after and if [Privileged Access Manager](https://cloud.google.com/iam/docs/temporary-elevated-access)

also see 

* [Vault Secrets for GCP Credential Access Boundary and Impersonation](https://github.com/salrashid123/vault-plugin-secrets-gcp-cab)

>> >>> **IMPORTANT**  If you are just looking for code samples that generate downscoped tokens, just use the official samples here [https://cloud.google.com/iam/docs/downscoping-short-lived-credentials#exchange-credential-auto](https://cloud.google.com/iam/docs/downscoping-short-lived-credentials#exchange-credential-auto)


### Defining a boundary rule:

You downscope a token by transmitting that token to a google token endpoint along with a boundary rule describing the resources and roles the credential should be restricted to.

The definition of a boundary rule is just json:

```json
{
  "accessBoundaryRules": [
    {
      "availableResource": "string",
      "availablePermissions": [
        "list"
      ],
      "availabilityCondition": {
        "title": "string",
        "expression": "string"
      }
    }
  ]
}
```

where

* `AvailableResource` (required)
This is the GCP resource (such as organization, folder, project, bucket, etc) to which access may be allowed (and allowed to resources below that resource if applicable). It must be in the format of a GCP Resource Name.


* `AvailablePermissions` (required)
This is a list of permissions that may be allowed for use on the specified resource or resources below the specified resource. The only supported value is IAM role with syntax: "inRole:roles/storage.admin"

* `AvailabilityCondition` (optional)
This describes additional fine-grained constraints to apply to the token.  The `expression` parameter describes the resource condition this rule applies to in [CEL Format](https://cloud.google.com/iam/docs/conditions-overview#cel). 

As an example, the following would the token to just `objectViewer` on `BUCKET_2` and specifically an object (prefix) `/foo.txt`

```json
{
  "accessBoundary" : {
      "accessBoundaryRules" : [
        {
          "availableResource" : "//storage.googleapis.com/projects/_/buckets/$BUCKET_2",
          "availablePermissions": ["inRole:roles/storage.objectViewer"],
          "availabilityCondition" : {
            "title" : "obj-prefixes",
            "expression" : "resource.name.startsWith(\"projects/_/buckets/$BUCKET_2/objects/foo.txt\")"
          }
        }
      ]
  }
}
```

* `AvailabilityConditions` (optional)
This defines restrictions on the resource based on a condition such as the path or prefix within that path.  Use an `availabilityCondition` to define such things as a specific object this downscoped token could access or the path prefix the token has permissions on.

### Exchange the token

You now need to transmit the original `access_token` and the boundary rule to a google token-exchange endpoint: `https://sts.googleapis.com/v1/token`.  The response JSON will return a new token if the policy file is well formed.  


### Usage: curl

As a quick demonstration of this capability, create two GCS buckets and acquire an `access_token` that you can use to list objects in either bucket.   Exchange that `access_token` for another `access_token` that _can only be used_ on one of the two buckets:

1. Create two buckets:

```bash
export PROJECT_ID=`gcloud config get-value core/project`

export BUCKET_1=$PROJECT_ID-1
export BUCKET_2=$PROJECT_ID-1-suffix

gsutil mb gs://$BUCKET_1
gsutil mb gs://$BUCKET_2
echo "foo" > someobject.txt
gsutil cp someobject.txt gs://$BUCKET_2/
```

2. Create policy to only allow `storage.objectViewer` to `BUCKET_2` AND on object `/someobject.txt`

```bash
cat <<EOF > access_boundary_2.json
{
  "accessBoundary" : {
      "accessBoundaryRules" : [
        {
          "availableResource" : "//storage.googleapis.com/projects/_/buckets/$BUCKET_2",
          "availablePermissions": ["inRole:roles/storage.objectViewer"],
          "availabilityCondition" : {
            "title" : "obj-prefixes",
            "expression" : "resource.name.startsWith(\"projects/_/buckets/$BUCKET_2/objects/someobject.txt\")"
          }
        }
      ]
  }
}

EOF
```

3. Get existing `access_token`

```
export TOKEN=`gcloud auth application-default print-access-token`
```

4. Exchange `access_token`

(the following command uses [jq](https://stedolan.github.io/jq/download/) to parse the response):

```bash
NEW_TOKEN_1=`curl -s -H "Content-Type:application/x-www-form-urlencoded" -X POST https://sts.googleapis.com/v1/token -d 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token_type=urn:ietf:params:oauth:token-type:access_token&requested_token_type=urn:ietf:params:oauth:token-type:access_token&subject_token='$TOKEN --data-urlencode "options=$(cat access_boundary_2.json)" | jq -r '.access_token'`
```

5. Use downscoped token

Try listing bucket contents using the new token...

as you'll see, you can access `BUCKET_2` but not `BUCKET_1` since we're using the downscoped token
```bash
curl -s -H "Authorization: Bearer $NEW_TOKEN_1"  -o /dev/null  -w "%{http_code}\n" https://storage.googleapis.com/storage/v1/b/$BUCKET_1/o

curl -s -H "Authorization: Bearer $NEW_TOKEN_1"  -o /dev/null  -w "%{http_code}\n" https://storage.googleapis.com/storage/v1/b/$BUCKET_2/o
```

### Usecase: TokenBroker

1. A developer or workload (the requester) requests credentials to get access to certain GCP resources.
2. The broker system identifies the service account that has access to the requested resources and gets a credential for that service account.
3. Before handing off the credentials to the requestor, the broker system restricts the permissions on the credential to only what the token requester needs access to, by applying a `Credential Access Boundary` on the service account credentials by calling the CAB API.
4. The broker system hands the downscoped credentials to the requester.
5. The requester can now use those credentials to access GCP resources

This release has the following restrictions:


![images/tokenflow.png](images/tokenflow.png)

---


Thats it...this is just an alpha capability...do not use this in production yet!.  Again, the intent of this article is to get feedback...please add in any comments to the repo here [https://github.com/salrashid123/downscoped_token](https://github.com/salrashid123/downscoped_token):
