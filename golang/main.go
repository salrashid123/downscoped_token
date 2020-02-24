package main

import (
	"context"
	"io/ioutil"
	"log"

	"cloud.google.com/go/storage"
	sal "github.com/salrashid123/oauth2/google"
	"google.golang.org/api/iterator"
	"google.golang.org/api/option"

	"golang.org/x/oauth2/google"
)

const (
	serviceAccountFile = "/path/to/svc_account.json"
)

var (
	projectID  = "yourproject"
	bucketName = "yourbucket"
	folder     = ""
)

func main() {

	ctx := context.Background()

	// rootTokenSource, err := google.DefaultTokenSource(ctx, "https://www.googleapis.com/auth/cloud-platform")
	// if err != nil {
	// 	log.Fatal(err)
	// }

	data, err := ioutil.ReadFile(serviceAccountFile)
	if err != nil {
		log.Fatal(err)
	}

	creds, err := google.CredentialsFromJSON(ctx, data, "https://www.googleapis.com/auth/cloud-platform")
	if err != nil {
		log.Fatal(err)
	}
	rootTokenSource := creds.TokenSource

	downScopedTokenSource, err := sal.DownScopedTokenSource(
		&sal.DownScopedTokenConfig{
			RootTokenSource: rootTokenSource,
			AccessBoundaryRules: []sal.AccessBoundaryRule{
				sal.AccessBoundaryRule{
					AvailableResource: "//storage.googleapis.com/projects/_/buckets/" + bucketName,
					AvailablePermissions: []string{
						"inRole:roles/storage.objectViewer",
					},
				},
			},
		},
	)

	// tok, err := downScopedTokenSource.Token()
	// if err != nil {
	// 	log.Fatal(err)
	// }

	// log.Printf("Downscoped Token: %v\n", tok.AccessToken)

	// stc := oauth2.StaticTokenSource(tok)

	// client := &http.Client{
	// 	Transport: &oauth2.Transport{
	// 		Source: stc,
	// 	},
	// }

	// url := fmt.Sprintf("https://storage.googleapis.com/storage/v1/b/%s/o", bucketName)
	// resp, err := client.Get(url)
	// if err != nil {
	// 	log.Fatal(err)
	// }
	// log.Printf("Response: %v", resp.Status)

	// Using google-cloud library

	storageClient, err := storage.NewClient(ctx, option.WithTokenSource(downScopedTokenSource))
	if err != nil {
		log.Fatalf("Could not create storage Client: %v", err)
	}

	it := storageClient.Bucket(bucketName).Objects(ctx, &storage.Query{
		Prefix: folder,
	})
	for {

		attrs, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			log.Fatal(err)
		}
		log.Println(attrs.Name)
	}

}
