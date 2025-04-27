FROM node:18-slim

RUN apt-get update || : && apt-get install -y

RUN apt-get install -y ca-certificates wget

WORKDIR /usr/src/app
COPY package*.json ./
COPY version.txt ./

COPY . ./
RUN npm install --only=production

# Add version label
ARG VERSION
LABEL version=$VERSION

CMD [ "node", "build/src/index.js" ]

