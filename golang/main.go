package main

import (
	"context"
	"io"
	"log"
	"os"

	"cloud.google.com/go/storage"
	sal "github.com/salrashid123/oauth2/downscoped"
	"google.golang.org/api/option"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

var (
	projectID  = "your-project"
	bucketName = "your-bucket"
)

func main() {

	ctx := context.Background()

	rootTokenSource, err := google.DefaultTokenSource(ctx,
		"https://www.googleapis.com/auth/iam")
	if err != nil {
		log.Fatal(err)
	}

	downScopedTokenSource, err := sal.DownScopedTokenSource(
		&sal.DownScopedTokenConfig{
			RootTokenSource: rootTokenSource,
			DownscopedOptions: sal.DownscopedOptions{
				AccessBoundary: sal.AccessBoundary{
					AccessBoundaryRules: []sal.AccessBoundaryRule{
						sal.AccessBoundaryRule{
							AvailableResource: "//storage.googleapis.com/projects/_/buckets/" + bucketName,
							AvailablePermissions: []string{
								"inRole:roles/storage.objectViewer",
							},
							AvailabilityCondition: sal.AvailabilityCondition{
								Title:      "obj-prefixes",
								Expression: "resource.name.startsWith(\"projects/_/buckets/srashid-1/objects/foo.txt\")",
							},
						},
					},
				},
			},
		},
	)

	// You can use the downscopeToken in the storage client below...but realistically,
	// you would generate a rootToken, downscope it and then provide the new token to another client
	// to use...similar to the bit below where the token itself is used to setup a StaticTokenSource
	tok, err := downScopedTokenSource.Token()
	if err != nil {
		log.Fatal(err)
	}
	// log.Println("Downscoped Token: %s", tok.AccessToken)

	sts := oauth2.StaticTokenSource(tok)

	storageClient, err := storage.NewClient(ctx, option.WithTokenSource(sts))
	if err != nil {
		log.Fatalf("Could not create storage Client: %v", err)
	}

	bkt := storageClient.Bucket(bucketName)
	obj := bkt.Object("foo.txt")
	r, err := obj.NewReader(ctx)
	if err != nil {
		panic(err)
	}
	defer r.Close()
	if _, err := io.Copy(os.Stdout, r); err != nil {
		panic(err)
	}
}
