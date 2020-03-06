# Copyright 2018 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Google Cloud Downscoped credentials.

 DownScoped credentials allows for exchanging a parent Credential's
 access_token for another access_token that has permissions on a limited set
 of resources the parent token originally had.

 For example, if the parent Credential that represents user Alice has access
 to GCS buckets A, B, C, you can exchange the Alice's credential for another
 credential that still identifies Alice but can only be used against Bucket A
 and C.

 DownScoped tokens currently only works for GCS resources.

 ** NOTE:** DownScoped tokens currently only works for GCS buckets and cannot
 be applied (yet) at the bucket+path or object level. The GCS bucket must be
 enabled with [Uniform bucket-level
 access](https://cloud.google.com/storage/docs/uniform-bucket-level-access

 DownScoped tokens are normally used in a tokenbroker/exchange service where
 you can mint a new restricted token to hand to a client. The sample below
 shows how to generate a downscoped token, extract the raw access_token, and
 then inject the raw token in another TokenSource (instead of just using the
 DownScopedToken as the TokenSource directly in the storageClient.).


 The following shows how to exchange a root credential for a downscoped
 credential that can only be used as roles/storage.objectViewer against GCS
 bucketA

"""

import base64
import copy
from datetime import datetime, timedelta
import json

import six
from six.moves import http_client

from google.auth import _helpers
from google.auth import credentials
from google.auth import exceptions
from google.auth.transport.requests import AuthorizedSession
from google.auth.credentials import AnonymousCredentials

import google.auth.transport.requests
import requests

_STS_ENDPOINT = "https://securetoken.googleapis.com/v2beta1/token"

_REFRESH_ERROR = "Unable to acquire downscoped credentials"

_TOKEN_INFO_ERROR = "Unable to determine root token expiry time"

_TOKEN_INFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/tokeninfo"


class Credentials(credentials.Credentials):
    """Use this Credential to limit the resources a credential can access on GCP.  For example,
    if a given TokenSource can access GCS buckets A and B, a DownScopedTokenSource derived from
    the root would represent the _same_ user but IAM permissions are restricted to bucket A.
    For more information, see:
    `Using Credential Access Boundary (DownScoped) Tokens`_ :

    .. _Using Credential Access Boundary (DownScoped) Tokens:
        https://github.com/salrashid123/downscoped_token

    Usage:

    Initialize a source credential with wide permissions on all buckets::

        from google.oauth2 import service_acccount
        from google.auth import downscoped_credentials

        target_scopes = [
            'https://www.googleapis.com/auth/devstorage.read_only']

        source_credentials = (
            service_account.Credentials.from_service_account_file(
                '/path/to/svc_account.json',
                scopes=target_scopes))

        # or just use default credentials
        # source_credentials, project_id = google.auth.default()

    Now use the source credentials to acquire a downscoped credential limited to a policy::

        json_document = {
            "accessBoundaryRules" : [
            {
                "availableResource" : "//storage.googleapis.com/projects/_/buckets/" + bucket_name,
                "availablePermissions": ["inRole:roles/storage.objectViewer"]
            }
            ]
        }

        dc = downscoped_credentials.Credentials(source_credentials=source_credentials,access_boundary_rules=json_document)

    Use the credential to access a resource access is granted::

        client = storage.Client(credentials=dc)
        for blob in blobs:
          print(blob.name)
    """

    def __init__(
        self,
        source_credentials,
        access_boundary_rules={},
    ):
        """
        Args:
            source_credentials (google.auth.Credentials): The source credential
                used as to acquire the downscoped credentials.
            access_boundary_rules (Sequence):   JSON structure format for a list
              bound tokens
                {
                    "accessBoundaryRules" : [
                    {
                        "availableResource" : "//storage.googleapis.com/projects/_/buckets/bucketA",
                        "availablePermissions": ["inRole:roles/storage.objectViewer"]
                    }
                    ]
                }
        """

        super(Credentials, self).__init__()

        self._source_credentials = copy.copy(source_credentials)
        if not access_boundary_rules.has_key('accessBoundaryRules'):
            raise exceptions.GoogleAuthError(
                "Provided access_boundary_rules must include accessBoundaryRules dictionary key"
            )
        self._access_boundary_rules = access_boundary_rules
        self.token = None
        self.expiry = _helpers.utcnow()

    @_helpers.copy_docstring(credentials.Credentials)
    def refresh(self, request):
        self._update_token(request)

    @property
    def expired(self):
        return _helpers.utcnow() >= self.expiry

    def _update_token(self, request):
        """Updates credentials with a new access_token representing
        the downscoped credentials.

        Args:
            request (google.auth.transport.requests.Request): Request object
                to use for refreshing credentials.
        """

        # Refresh our source credentials.
        self._source_credentials.refresh(request)

        request = google.auth.transport.requests.Request()

        ac = AnonymousCredentials()
        authed_session = AuthorizedSession(credentials=ac)

        body = {
            "grant_type": 'urn:ietf:params:oauth:grant-type:token-exchange',
            "subject_token_type": 'urn:ietf:params:oauth:token-type:access_token',
            "requested_token_type": 'urn:ietf:params:oauth:token-type:access_token',
            "subject_token": self._source_credentials.token,
            "access_boundary": json.dumps(self._access_boundary_rules)
        }

        resp = authed_session.post(_STS_ENDPOINT, data=body)
        if resp.status_code != http_client.OK:
            raise exceptions.RefreshError(_REFRESH_ERROR)

        data = resp.json()
        self.token = data['access_token']

        if data.has_key('expires_in'):
            self.expiry = datetime.now() + \
                timedelta(seconds=int(data['expires_in']))
        else:
            authed_session = AuthorizedSession(credentials=ac)
            payload = {'access_token': self._source_credentials.token}
            token_response = authed_session.get(
                _TOKEN_INFO_ENDPOINT, params=payload)
            if token_response.status_code != http_client.OK:
                raise exceptions.RefreshError(_TOKEN_INFO_ERROR)
            tokeninfo_data = token_response.json()
            self.expiry = datetime.now() + \
                timedelta(seconds=int(tokeninfo_data['expires_in']))
