FROM node:18-slim

# Install required dependencies
RUN apt-get update || : && apt-get install -y ca-certificates wget

# Set the working directory
WORKDIR /usr/src/app

# Copy all project files first (including tsconfig.json & src)
COPY . ./

# Install dependencies AFTER copying all source files
RUN npm install --only=production

# Expose port 8080 for Cloud Run
EXPOSE 8080

# Start the application
CMD ["node", "build/src/index.js"]
