package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"

	"cloud.google.com/go/storage"
	"golang.org/x/oauth2/google"
	"google.golang.org/api/option"

	resourcemanager "cloud.google.com/go/resourcemanager/apiv3"
	resourcemanagerpb "cloud.google.com/go/resourcemanager/apiv3/resourcemanagerpb"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google/downscope"
	"google.golang.org/api/iterator"

	//kms "cloud.google.com/go/kms/apiv1"
	//"cloud.google.com/go/kms/apiv1/kmspb"

	"google.golang.org/api/compute/v1"
)

var (
	bucketName = "core-eso-bucket"
)

const (
	projectID = "core-eso"
)

func main() {

	ctx := context.Background()

	// GCS (supported)

	accessBoundary := []downscope.AccessBoundaryRule{
		{
			AvailableResource:    fmt.Sprintf("//storage.googleapis.com/projects/_/buckets/%s", bucketName),
			AvailablePermissions: []string{"inRole:roles/storage.objectViewer"},
			Condition: &downscope.AvailabilityCondition{
				Expression: "resource.name.startsWith(\"projects/_/buckets/core-eso-bucket/objects/foo.txt\")",
			},
		},
	}

	var rootSource oauth2.TokenSource

	rootSource, err := google.DefaultTokenSource(ctx, "https://www.googleapis.com/auth/cloud-platform")
	if err != nil {
		fmt.Printf("failed to get root source: %v", err)
		return
	}
	dts, err := downscope.NewTokenSource(ctx, downscope.DownscopingConfig{RootSource: rootSource, Rules: accessBoundary})
	if err != nil {
		fmt.Printf("failed to generate downscoped token source: %v", err)
		return
	}

	storageClient, err := storage.NewClient(ctx, option.WithTokenSource(dts))
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

	fmt.Println()

	// *************************************
	// KMS (not supported)

	//kms "cloud.google.com/go/kms/apiv1"
	//"cloud.google.com/go/kms/apiv1/kmspb"

	// kmsaccessBoundary := []downscope.AccessBoundaryRule{
	// 	{
	// 		AvailableResource:    "//cloudkms.googleapis.com/projects/core-eso/locations/us-central1/keyRings/mykeyring/cryptoKeys/key1/cryptoKeyVersions/1",
	// 		AvailablePermissions: []string{"inRole:roles/cloudkms.cryptoKeyEncrypter"},
	// 	},
	// }

	// kdts, err := downscope.NewTokenSource(ctx, downscope.DownscopingConfig{RootSource: rootSource, Rules: kmsaccessBoundary})
	// if err != nil {
	// 	fmt.Printf("failed to generate downscoped token source: %v", err)
	// 	return
	// }

	// client, err := kms.NewKeyManagementClient(ctx, option.WithTokenSource(kdts))
	// if err != nil {
	// 	log.Fatalf("failed to setup client: %v", err)
	// }
	// defer client.Close()

	// plaintext := []byte("foo")

	// req := &kmspb.EncryptRequest{
	// 	Name:      "projects/core-eso/locations/us-central1/keyRings/mykeyring/cryptoKeys/key1/cryptoKeyVersions/1",
	// 	Plaintext: plaintext,
	// }

	// // Call the API.
	// result, err := client.Encrypt(ctx, req)
	// if err != nil {
	// 	panic(err)
	// }

	// fmt.Printf("Encrypted ciphertext: %s", base64.StdEncoding.EncodeToString(result.Ciphertext))

	// *************************************
	// CRM (supported)

	// resourcemanager "cloud.google.com/go/resourcemanager/apiv3"
	// resourcemanagerpb "cloud.google.com/go/resourcemanager/apiv3/resourcemanagerpb"

	folderID := "750467892309"
	crmaccessBoundary := []downscope.AccessBoundaryRule{
		{
			AvailableResource:    fmt.Sprintf("//cloudresourcemanager.googleapis.com/folders/%s", folderID),
			AvailablePermissions: []string{"inRole:roles/resourcemanager.folderViewer"},
		},
	}

	crmts, err := downscope.NewTokenSource(ctx, downscope.DownscopingConfig{RootSource: rootSource, Rules: crmaccessBoundary})
	if err != nil {
		fmt.Printf("failed to generate downscoped token source: %v", err)
		return
	}

	c, err := resourcemanager.NewProjectsClient(ctx, option.WithTokenSource(crmts))
	if err != nil {
		panic(err)
	}
	defer c.Close()

	req := &resourcemanagerpb.ListProjectsRequest{
		Parent: fmt.Sprintf("folders/%s", folderID),
	}
	it := c.ListProjects(ctx, req)
	for {
		resp, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			panic(err)
		}
		fmt.Printf("Project %s\n", resp.Name)
	}

	// *************************************
	// BigQuery (not supported)

	// "cloud.google.com/go/bigquery"
	// "google.golang.org/api/iterator"

	// bqaccessBoundary := []downscope.AccessBoundaryRule{
	// 	{
	// 		AvailableResource:    fmt.Sprintf("//bigquery.googleapis.com/projects/%s/datasets/%s/tables/%s", projectID, "echo_dataset", "echorequest"),
	// 		AvailablePermissions: []string{"inRole:roles/bigquery.dataViewer"},
	// 	},
	// }

	// //bigquery.googleapis.com/projects/(.+)/datasets/(.+)/tables/

	// bqts, err := downscope.NewTokenSource(ctx, downscope.DownscopingConfig{RootSource: rootSource, Rules: bqaccessBoundary})
	// if err != nil {
	// 	fmt.Printf("failed to generate downscoped token source: %v", err)
	// 	return
	// }

	// client, err := bigquery.NewClient(ctx, projectID, option.WithTokenSource(bqts))
	// if err != nil {
	// 	panic(err)
	// }
	// defer client.Close()

	// t := client.Dataset("echo_dataset").Table("echorequest")

	// md, err := t.Metadata(ctx)
	// if err != nil {
	// 	panic(err)
	// }

	// fmt.Printf("%v\n", md)

	// *************************************

	// COMPUTE

	// "google.golang.org/api/compute/v1"

	zone := "us-central1-a"
	instanceName := "attestor"

	computeaccessBoundary := []downscope.AccessBoundaryRule{
		{
			AvailableResource:    fmt.Sprintf("//compute.googleapis.com/projects/%s/zones/%s/instances/%s", projectID, zone, instanceName),
			AvailablePermissions: []string{"inRole:roles/compute.viewer"},
		},
	}

	computets, err := downscope.NewTokenSource(ctx, downscope.DownscopingConfig{RootSource: rootSource, Rules: computeaccessBoundary})
	if err != nil {
		fmt.Printf("failed to generate downscoped token source: %v", err)
		return
	}
	computeService, err := compute.NewService(ctx, option.WithTokenSource(computets))
	if err != nil {
		panic(err)
	}

	inst, err := computeService.Instances.Get(projectID, zone, instanceName).Do()
	if err != nil {
		panic(err)
	}

	fmt.Printf("InstanceID %d\n", inst.Id)

}
