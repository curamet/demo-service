#!/bin/bash

set -e

# === 0. Set paths ===
TFVARS_FILE="terraform/environments/dev/terraform.tfvars"
BACKEND_CONF="terraform/environments/dev/backend.conf"
VERSION_KEY="run_version"

# === 1. Extract and increment version ===
CURRENT_VERSION=$(grep "^$VERSION_KEY" "$TFVARS_FILE" | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
NEW_PATCH=$((PATCH + 1))
NEW_VERSION="$MAJOR.$MINOR.$NEW_PATCH"

echo "🔁 Updating version: $CURRENT_VERSION → $NEW_VERSION"

# === 2. Update the tfvars file ===
sed -i '' "s/^$VERSION_KEY *= *\".*\"/$VERSION_KEY         = \"$NEW_VERSION\"/" "$TFVARS_FILE"

# === 3. Build and push Docker image ===
IMAGE="europe-docker.pkg.dev/curamet-onboarding/am-curamet-repo/am-demo-service:$NEW_VERSION"
echo "🐳 Building Docker image: $IMAGE"
docker buildx build --platform linux/amd64 -t "$IMAGE" . --push

# === 4. Run terraform init ===
echo "🚀 Running terraform init with backend config..."
terraform init --backend-config="$BACKEND_CONF"

echo "✅ Done! Version bumped to $NEW_VERSION and Docker image pushed"
