#!/bin/bash

set -e

if [ -z "$IMAGE_URL" ]; then
  echo "❌ ERROR: IMAGE_URL environment variable is not set!"
  exit 1
fi

# Build and push the Docker image targeting linux/amd64
docker buildx build --platform linux/amd64 --push -t "$IMAGE_URL" ../.

echo "✅ Successfully built and pushed image: $IMAGE_URL"
