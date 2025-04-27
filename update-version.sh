#!/bin/bash

# Check if version argument is provided
if [ $# -ne 1 ]; then
    echo "Usage: $0 <new_version>"
    echo "Example: $0 1.0.0"
    exit 1
fi

NEW_VERSION=$1
ROOT_DIR=$(pwd)

# Update version.txt
echo $NEW_VERSION > version.txt
echo "Updated version.txt to $NEW_VERSION"

# Update package.json version
npm version $NEW_VERSION --no-git-tag-version
echo "Updated package.json to version $NEW_VERSION"

echo "Version updated successfully across all components!"
echo "Terraform will automatically use version $NEW_VERSION on next apply" 