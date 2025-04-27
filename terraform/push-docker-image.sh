#!/bin/bash

# Get the version from version.txt
VERSION=$(cat ../../version.txt)

# Docker build and push commands
docker build --platform linux/amd64 --build-arg VERSION=$VERSION --tag "$IMAGE_URL" ../.
docker push "$IMAGE_URL"


