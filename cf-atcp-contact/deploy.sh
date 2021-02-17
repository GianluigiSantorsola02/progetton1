#!/usr/bin/env bash
#
# Script to prepare and deploy function.

set -e

if (( $# != 1 )); then
  echo 'usage: deploy.sh <GCP_PROJECT>'
  exit 1
fi

PROJECT_ID="$1"
SECRET_ID='cf-atcp-contact'
CF_NAME='cf-atcp-contact'
SA_ACCT="cf-atcp-contact@$PROJECT_ID.iam.gserviceaccount.com"

echo 'Rendering env vars file...'
gcloud --project=$PROJECT_ID \
  secrets versions access latest \
  --secret=$SECRET_ID > env.yaml

gcloud --project=$PROJECT_ID functions deploy $CF_NAME \
  --entry-point org.broadinstitute.ddp.cf.ATCPStudyContact \
  --trigger-http \
  --allow-unauthenticated \
  --service-account $SA_ACCT \
  --env-vars-file env.yaml \
  --region us-central1 \
  --runtime java11 \
  --memory 256MB \
  --timeout 1m \
  --max-instances 3
