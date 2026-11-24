import com.tetrapak.ot.ReleasePolicy
import groovy.json.JsonOutput

/*
 * OT release pipeline for Converto services.
 *
 *   Branch / PR build : build, unit tests, SAST, SCA, image build and scan. Nothing is pushed or signed.
 *   Tag build         : + signed tag on the release branch, push, digest, SBOM, then sign and attest
 *                       on a separate signing agent and verify everything before the build passes.
 *
 * Service repositories pass only serviceName (and optionally dockerfile / buildContext).
 * Every security setting comes from ReleasePolicy and cannot be overridden.
 */
def call(Map cfg = [:]) {
    Map P = ReleasePolicy.VALUES
    Map svc = validateConfig(cfg, P)

    pipeline {
        agent { label P.buildAgent }

        options {
            timestamps()
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
            timeout(time: 90, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30'))   // release builds are kept forever (see post)
        }

        environment {
            SERVICE       = "${svc.name}"
            IMAGE_REPO    = "${svc.repository}"
            DOCKERFILE    = "${svc.dockerfile}"
            BUILD_CONTEXT = "${svc.context}"
            EVIDENCE      = 'evidence'
        }

        stages {
            stage('Checkout & Policy') {
                steps {
                    deleteDir()
                    script {
                        def scmVars = checkout scm
                        env.GIT_COMMIT  = scmVars.GIT_COMMIT
                        env.GIT_URL     = scmVars.GIT_URL
                        env.SCAN_REF    = env.TAG_NAME ?: env.BRANCH_NAME
                        env.VERSION     = env.TAG_NAME ?: "dev-${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(12)}"
                        env.LOCAL_IMAGE = "ot-local/${svc.name}:${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(12)}"
                    }
                    sh '''#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p "$EVIDENCE"
# Every base image must be pinned by digest (scratch and earlier build stages excepted).
aliases="$(awk 'toupper($1)=="FROM" {for (i=2;i<=NF;i++) if (toupper($i)=="AS") print $(i+1)}' "$DOCKERFILE")"
awk 'toupper($1)=="FROM" {i=2; while ($i ~ /^--/) i++; print $i}' "$DOCKERFILE" |
while read -r image; do
  [ "$image" = scratch ] && continue
  grep -qxF "$image" <<<"$aliases" && continue
  [[ "$image" == *@sha256:* ]] || { echo "Base image not pinned by digest: $image" >&2; exit 1; }
done
'''
                }
            }

            stage('Verify Release Tag') {
                when { buildingTag() }
                steps {
                    script {
                        if (!(env.TAG_NAME ==~ P.releaseTagPattern)) {
                            error("Tag '${env.TAG_NAME}' does not match ${P.releaseTagPattern}")
                        }
                        writeFile file: '.ot/release-signers.asc', text: libraryResource(P.releaseSignersResource)
                    }
                    withEnv(["RELEASE_BRANCH=${P.releaseBranch}"]) {
                        sh '''#!/usr/bin/env bash
set -Eeuo pipefail
git fetch --no-tags origin \
  "+refs/heads/$RELEASE_BRANCH:refs/remotes/origin/$RELEASE_BRANCH" \
  "+refs/tags/$TAG_NAME:refs/tags/$TAG_NAME"

# The tag points at the built commit, and that commit is on the release branch.
test "$(git rev-list -n 1 "refs/tags/$TAG_NAME")" = "$GIT_COMMIT"
git merge-base --is-ancestor "$GIT_COMMIT" "origin/$RELEASE_BRANCH"

# Only keys shipped with the shared library may sign release tags (no agent keyring).
export GNUPGHOME="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME"' EXIT
gpg --batch --quiet --import .ot/release-signers.asc
git verify-tag "$TAG_NAME" > "$EVIDENCE/tag-verification.txt" 2>&1
'''
                    }
                }
            }

            stage('Build & Test') {
                agent {
                    docker {
                        image P.mavenImage
                        reuseNode true
                    }
                }
                steps {
                    configFileProvider([configFile(fileId: P.mavenSettingsId, variable: 'MAVEN_SETTINGS')]) {
                        sh '''#!/usr/bin/env bash
set -Eeuo pipefail
# Per-build local repository: no Maven cache shared between builds or trust levels.
mvn -B -ntp -s "$MAVEN_SETTINGS" -Dmaven.repo.local="$WORKSPACE/.m2/repository" verify \
  2>&1 | tee "$EVIDENCE/maven-verify.log"
'''
                    }
                }
                post {
                    always { junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: false }
                }
            }

            stage('SAST & SCA') {
                agent {
                    docker {
                        image P.mavenImage
                        reuseNode true
                    }
                }
                steps {
                    script {
                        assertPassed('Polaris', polarisScanStep(
                            application_name  : P.scan.application,
                            project_name      : svc.name,
                            branch_name       : env.SCAN_REF,
                            assessment_types  : P.scan.polarisAssessment,
                            test_sast_type    : P.scan.polarisSastType,
                            test_sast_location: P.scan.polarisSastLocation))
                        assertPassed('Black Duck', blackDuckScanStep(svc.name, env.SCAN_REF, P.scan.blackDuckGroup))
                    }
                }
            }

            stage('Image Build & Scan') {
                steps {
                    withEnv(["TRIVY_SEVERITY=${P.trivy.severity}", "TRIVY_IGNORE_UNFIXED=${P.trivy.ignoreUnfixed}"]) {
                        sh '''#!/usr/bin/env bash
set -Eeuo pipefail
docker build --pull --no-cache \
  --file "$DOCKERFILE" \
  --label "org.opencontainers.image.revision=$GIT_COMMIT" \
  --label "org.opencontainers.image.version=$VERSION" \
  --label "org.opencontainers.image.source=$GIT_URL" \
  --tag "$LOCAL_IMAGE" "$BUILD_CONTEXT" 2>&1 | tee "$EVIDENCE/image-build.log"

# Gate: fixed policy from ReleasePolicy (TRIVY_SEVERITY / TRIVY_IGNORE_UNFIXED).
trivy image --exit-code 1 --format json --output "$EVIDENCE/image-scan.json" "$LOCAL_IMAGE"

# Release: same result in cosign-vuln format, attested to the digest later.
if [ -n "${TAG_NAME:-}" ]; then
  trivy image --skip-db-update --exit-code 0 --format cosign-vuln \
    --output "$EVIDENCE/image-vuln.json" "$LOCAL_IMAGE"
fi
'''
                    }
                }
            }

            stage('Publish') {
                when { buildingTag() }
                steps {
                    script {
                        withRegistry(P, 'docker') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
docker tag "$LOCAL_IMAGE" "$IMAGE_REPO:$TAG_NAME"
docker push "$IMAGE_REPO:$TAG_NAME" 2>&1 | tee "$EVIDENCE/image-push.log"

# Digest of exactly what was pushed, read locally (no re-pull by mutable tag).
docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$LOCAL_IMAGE" \
  | grep "^$IMAGE_REPO@sha256:" > "$EVIDENCE/image-ref.txt"

syft scan "registry:$(cat "$EVIDENCE/image-ref.txt")" \
  --output "cyclonedx-json=$EVIDENCE/sbom.cdx.json"
'''
                        }
                        env.IMAGE_REF = readFile("${env.EVIDENCE}/image-ref.txt").trim()
                        if (!(env.IMAGE_REF ==~ /^[^@\s]+@sha256:[a-f0-9]{64}$/)) {
                            error("Expected exactly one pushed digest, got: ${env.IMAGE_REF}")
                        }
                        writeJson("${env.EVIDENCE}/provenance.json", provenance(P, svc))
                        writeJson("${env.EVIDENCE}/release-record.json", releaseRecord(P, svc))
                    }
                    stash name: 'release-inputs',
                          includes: 'evidence/sbom.cdx.json,evidence/image-vuln.json,evidence/provenance.json,evidence/release-record.json'
                }
            }

            stage('Sign & Attest') {
                when {
                    beforeAgent true
                    buildingTag()
                }
                agent { label P.signAgent }
                environment { RECORD_TYPE = "${P.releaseRecordType}" }
                steps {
                    deleteDir()
                    unstash 'release-inputs'
                    script {
                        withRegistry(P, 'cosign') {
                            withCredentials([string(credentialsId: P.signing.vaultTokenCredentialsId, variable: 'VAULT_TOKEN')]) {
                                withEnv(["VAULT_ADDR=${P.signing.vaultAddr}", "SIGNING_KEY=${P.signing.keyRef}"]) {
                                    sh '''#!/usr/bin/env bash
set -Eeuo pipefail
sign=(--yes --key "$SIGNING_KEY" --tlog-upload=false --new-bundle-format=false --use-signing-config=false)
verify=(--key "$SIGNING_KEY" --insecure-ignore-tlog=true)
types=(cyclonedx vuln slsaprovenance1 "$RECORD_TYPE")
files=(evidence/sbom.cdx.json evidence/image-vuln.json evidence/provenance.json evidence/release-record.json)

cosign sign "${sign[@]}" "$IMAGE_REF"
for i in "${!types[@]}"; do
  cosign attest "${sign[@]}" --type "${types[$i]}" --predicate "${files[$i]}" "$IMAGE_REF"
done

# The build passes only if the signature and every attestation verify.
cosign verify "${verify[@]}" "$IMAGE_REF" > evidence/verify-signature.json
for t in "${types[@]}"; do
  cosign verify-attestation "${verify[@]}" --type "$t" "$IMAGE_REF" > "evidence/verify-${t//[^a-z0-9]/_}.json"
done
'''
                                }
                            }
                        }
                    }
                }
                post {
                    always { archiveArtifacts artifacts: 'evidence/verify-*.json', allowEmptyArchive: true }
                }
            }
        }

        post {
            always {
                archiveArtifacts artifacts: 'evidence/**', allowEmptyArchive: true, fingerprint: true
                sh 'docker image rm "${LOCAL_IMAGE:-none}" "${IMAGE_REPO}:${TAG_NAME:-none}" >/dev/null 2>&1 || true'
                cleanWs(deleteDirs: true, notFailBuild: true)
            }
            success {
                script {
                    if (env.TAG_NAME) { currentBuild.keepLog = true }   // release evidence is never rotated away
                    notifyTeams(P, 'SUCCESS')
                }
            }
            unsuccessful {
                script { notifyTeams(P, currentBuild.currentResult) }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

Map validateConfig(Map cfg, Map P) {
    List allowed = ['serviceName', 'dockerfile', 'buildContext']
    for (key in cfg.keySet()) {
        if (!(key in allowed)) {
            error("Unsupported otReleasePipeline setting '${key}'. Security settings are fixed in ReleasePolicy.")
        }
    }

    String name = cfg.serviceName?.toString()?.trim()
    if (!(name ==~ /^[a-z0-9][a-z0-9-]{1,62}$/)) {
        error("serviceName must be lowercase letters, digits and hyphens: '${name}'")
    }

    String dockerfile = (cfg.dockerfile ?: 'Dockerfile').toString()
    String context = (cfg.buildContext ?: '.').toString()
    for (path in [dockerfile, context]) {
        if (!(path ==~ /^[A-Za-z0-9._\/-]+$/) || path.contains('..') || path.startsWith('/')) {
            error("Invalid relative path: '${path}'")
        }
    }

    if (!(P.mavenImage ==~ /^[^@]+@sha256:[a-f0-9]{64}$/)) {
        error('ReleasePolicy.mavenImage must be pinned by digest')
    }
    if (P.signing.vaultAddr == 'SET_ME') {
        error('ReleasePolicy.signing.vaultAddr is not set')
    }

    return [name      : name,
            dockerfile: dockerfile,
            context   : context,
            repository: "${P.registry}/${P.registryProject}/${name}".toString()]
}

// Fail closed: anything that is not an explicit pass fails the gate. The existing scanner
// steps throw on failure and return null on success, so null counts as a pass.
void assertPassed(String scanner, def result) {
    if (result == null || result == true) {
        return
    }
    String value = result.toString().trim().toLowerCase()
    if (!(value in ['success', 'passed', 'ok'])) {
        error("${scanner} gate failed (result: ${value.take(100)})")
    }
}

// Registry login into a per-build config dir; the password is only in scope for the login.
void withRegistry(Map P, String cli, Closure body) {
    if (!(cli in ['docker', 'cosign'])) {
        error("Unsupported registry client: ${cli}")
    }
    withEnv(["DOCKER_CONFIG=${env.WORKSPACE}/.docker-${env.BUILD_NUMBER}",
             "REGISTRY=${P.registry}",
             "REGISTRY_CLI=${cli}"]) {
        withCredentials([usernamePassword(credentialsId: P.harborCredentialsId,
                                          usernameVariable: 'REG_USER',
                                          passwordVariable: 'REG_PASS')]) {
            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p "$DOCKER_CONFIG"
printf '%s' "$REG_PASS" | "$REGISTRY_CLI" login "$REGISTRY" --username "$REG_USER" --password-stdin >/dev/null
'''
        }
        try {
            body()
        } finally {
            sh 'rm -rf "$DOCKER_CONFIG"'
        }
    }
}

// SLSA v1 provenance (predicate for cosign --type slsaprovenance1).
Map provenance(Map P, Map svc) {
    String builderRepo = P.mavenImage.split('@')[0]
    String builderDigest = P.mavenImage.split('@sha256:')[1]
    return [
        buildDefinition: [
            buildType           : P.provenance.buildType,
            externalParameters  : [repository: env.GIT_URL,
                                   ref       : "refs/tags/${env.TAG_NAME}".toString(),
                                   service   : svc.name],
            internalParameters  : [jenkinsJob: env.JOB_NAME, jenkinsBuild: env.BUILD_NUMBER],
            resolvedDependencies: [
                [uri: "git+${env.GIT_URL}@refs/tags/${env.TAG_NAME}".toString(), digest: [gitCommit: env.GIT_COMMIT]],
                [uri: "oci://${builderRepo}".toString(), digest: [sha256: builderDigest]]
            ]
        ],
        runDetails     : [
            builder : [id: P.provenance.builderId],
            metadata: [invocationId: env.BUILD_URL,
                       startedOn   : utc(new Date(currentBuild.startTimeInMillis))]
        ]
    ]
}

// Release record: what was released and which gates it passed. Signed and attached to the digest.
Map releaseRecord(Map P, Map svc) {
    return [
        product  : P.product,
        service  : svc.name,
        version  : env.TAG_NAME,
        image    : env.IMAGE_REF,
        gitCommit: env.GIT_COMMIT,
        gitUrl   : env.GIT_URL,
        gates    : [tagSignature: 'verified',
                    unitTests   : 'passed',
                    sast        : 'Polaris passed',
                    sca         : 'Black Duck passed',
                    imageScan   : "trivy ${P.trivy.severity}, ignoreUnfixed=${P.trivy.ignoreUnfixed}: passed".toString()],
        build    : [jenkinsJob: env.JOB_NAME, jenkinsBuild: env.BUILD_NUMBER, url: env.BUILD_URL],
        createdAt: utc(new Date())
    ]
}

void writeJson(String file, Map content) {
    writeFile file: file, text: JsonOutput.prettyPrint(JsonOutput.toJson(content)) + '\n'
}

String utc(Date date) {
    return date.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
}

void notifyTeams(Map P, String status) {
    try {
        office365ConnectorSend(
            credentialsId: P.teamsWebhookCredentialsId,
            adaptiveCards: true,
            color        : status == 'SUCCESS' ? '2EB886' : 'D13438',
            status       : status,
            message      : """**Converto OT release pipeline**
- **Service:** ${env.SERVICE}
- **Version:** ${env.VERSION ?: 'unknown'}
- **Image:** ${env.IMAGE_REF ?: 'not published'}
- **Build:** ${env.BUILD_URL}""")
    } catch (Exception e) {
        echo "WARNING: Teams notification failed (${e.getClass().getSimpleName()})"
    }
}
