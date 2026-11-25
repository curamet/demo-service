import groovy.json.JsonOutput
import groovy.transform.Field

/*
 * OT release pipeline – Converto services (converto-shared-lib).
 *
 *   Branch / PR build : checkout, build, tests, SAST, SCA, image build and scan. Nothing is pushed or signed.
 *   Tag build         : + signed release tag on master, push, immutable digest, SBOM, then sign and
 *                       attest on a separate signing agent, verify everything, publish evidence.
 *
 * Service repos may pass only: serviceName (required), dockerfile (optional).
 * Every security setting is fixed in POLICY below and changes only through a reviewed library release.
 */

@Field static final Map POLICY = [
    product           : 'converto',

    // IT Harbor. Robot account limited to the project (push + accessories); tag immutability enabled.
    registry          : 'hub.tetrapak.com',
    registryProject   : 'converto-harbor',
    harborCredentials : 'OT_RELEASE_HARBOR_ROBOT',

    // Dedicated ephemeral agents. The sign agent has no build tools and never receives source code.
    buildAgent        : 'ot-release-build',
    signAgent         : 'ot-release-sign',

    // Release trigger: signed annotated tag on master, signed by an approved release signer.
    releaseBranch     : 'master',
    releaseTagPattern : '^ot-release-[0-9]{4}\\.[0-9]{2}\\.[0-9]+$',
    tagSignersKeyring : 'OT_RELEASE_TAG_SIGNERS',   // secret file: armored public keys of approved signers

    // Toolchain: builder image pinned by digest; Maven resolves only through the Nexus mirror.
    mavenImage        : 'hub.tetrapak.com/shared-services/maven-docker-cli@sha256:SET_ME',
    mavenSettings     : 'converto-maven-settings',  // Config File Provider: settings.xml with Nexus mirror

    // Gates
    trivySeverity     : 'HIGH,CRITICAL',
    trivyIgnoreUnfixed: 'true',
    scanApplication   : 'Converto',
    blackDuckGroup    : 'Converto',
    polarisAssessment : 'SAST',
    polarisSastType   : 'SAST-FULL',
    polarisLocation   : 'local',

    // Signing: cosign key pair held as Jenkins credentials, scoped to the OT release folder and used
    // only on the sign agent. The public key is the one OT sites trust; it is also used to verify here.
    cosignPrivateKey  : 'COSIGN_PRIVATE_KEY',   // secret file: encrypted cosign.key
    cosignPassword    : 'COSIGN_PASSWORD',      // secret text: key password
    cosignPublicKey   : 'COSIGN_PUBLIC_KEY',    // secret file: cosign.pub (pinned, distributed to OT sites)

    buildType         : 'urn:tetrapak:build-type:jenkins:converto-ot-release:v1',
    builderId         : 'urn:tetrapak:builder:jenkins:converto-ot-ci:v1',
    releaseRecordType : 'urn:tetrapak:ot:release-record:v1',

    teamsWebhook      : 'OT_CICD_TEAMS_WEBHOOK',
].asImmutable()

def call(Map config = [:]) {
    Map P = POLICY
    Map svc = validateConfig(config)

    pipeline {
        agent { label P.buildAgent }

        options {
            timestamps()
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
            timeout(time: 90, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))   // release builds: keepLog
        }

        environment {
            SERVICE_NAME     = "${svc.name}"
            IMAGE_REPOSITORY = "${svc.repository}"
            HARBOR_REGISTRY  = "${P.registry}"
            DOCKERFILE       = "${svc.dockerfile}"
            RELEASE_BRANCH   = "${P.releaseBranch}"
            EVIDENCE_DIR     = 'evidence'
            SBOM_DIR         = 'evidence/sbom'
            SECURITY_DIR     = 'evidence/security'
            TEST_DIR         = 'evidence/tests'
            BUILD_DIR        = 'evidence/build'
        }

        stages {
            stage('Clean Checkout') {
                steps {
                    deleteDir()
                    script {
                        runGate('Clean Checkout') {
                            def scm = checkout scm
                            env.GIT_COMMIT = scm.GIT_COMMIT
                            env.GIT_URL = scm.GIT_URL
                            sh 'test "$(git rev-parse HEAD)" = "$GIT_COMMIT"'
                        }
                    }
                }
            }

            stage('Initialize Release Context') {
                steps {
                    script {
                        runGate('Initialize Release Context') {
                            String shortSha = env.GIT_COMMIT.take(12)
                            env.RELEASE_TAG = env.TAG_NAME ?: "dev-${env.BUILD_NUMBER}-${shortSha}"
                            env.SCAN_REF    = env.TAG_NAME ?: env.BRANCH_NAME
                            env.LOCAL_IMAGE = "ot-local/${svc.name}:${env.BUILD_NUMBER}-${shortSha}"
                            if (env.TAG_NAME) {
                                env.REMOTE_IMAGE_TAG = "${svc.repository}:${env.TAG_NAME}"
                                echo "Release build: ${env.REMOTE_IMAGE_TAG}"
                            } else {
                                echo 'Development build: publishing, signing and attestation stages are skipped.'
                            }
                        }
                    }
                }
            }

            stage('Initialize Release Evidence') {
                steps {
                    script {
                        runGate('Initialize Release Evidence') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p "$SBOM_DIR" "$SECURITY_DIR/polaris" "$SECURITY_DIR/blackduck" "$TEST_DIR" "$BUILD_DIR"
{
  echo "job=$JOB_NAME"; echo "buildNumber=$BUILD_NUMBER"; echo "buildUrl=$BUILD_URL"
  echo "gitCommit=$GIT_COMMIT"; echo "gitUrl=$GIT_URL"; echo "releaseTag=$RELEASE_TAG"
  echo "releaseBuild=${TAG_NAME:+true}"; echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$EVIDENCE_DIR/build-context.properties"
{ git --version; docker version --format '{{.Client.Version}}'; trivy --version; syft version; } \
  > "$EVIDENCE_DIR/tool-versions.txt" 2>&1
'''
                        }
                    }
                }
            }

            stage('Validate OT Release Trigger') {
                when { buildingTag() }
                steps {
                    script {
                        runGate('Validate OT Release Trigger') {
                            if (!(env.TAG_NAME ==~ P.releaseTagPattern)) {
                                error("Tag '${env.TAG_NAME}' does not match ${P.releaseTagPattern}")
                            }
                            withCredentials([file(credentialsId: P.tagSignersKeyring, variable: 'TAG_SIGNERS')]) {
                                sh '''#!/usr/bin/env bash
set -Eeuo pipefail
git fetch --no-tags origin \
  "+refs/heads/$RELEASE_BRANCH:refs/remotes/origin/$RELEASE_BRANCH" \
  "+refs/tags/$TAG_NAME:refs/tags/$TAG_NAME"

# Tag points at the built commit, and the commit is on the release branch.
test "$(git rev-list -n 1 "refs/tags/$TAG_NAME")" = "$GIT_COMMIT"
git merge-base --is-ancestor "$GIT_COMMIT" "origin/$RELEASE_BRANCH"

# Signed by an approved release signer only (temporary keyring, not the agent's).
export GNUPGHOME="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME"' EXIT
gpg --batch --quiet --import "$TAG_SIGNERS"
git verify-tag "$TAG_NAME" > "$EVIDENCE_DIR/git-tag-verification.txt" 2>&1

printf 'releaseTag=%s\\ngitCommit=%s\\nsourceBranch=%s\\n' "$TAG_NAME" "$GIT_COMMIT" "$RELEASE_BRANCH" \
  > "$EVIDENCE_DIR/release-trigger.properties"
git show --no-patch --format=fuller "$GIT_COMMIT" > "$EVIDENCE_DIR/git-commit.txt"
git show-ref --tags "$TAG_NAME" > "$EVIDENCE_DIR/git-tag-reference.txt"
'''
                            }
                        }
                    }
                }
            }

            stage('Validate Base Images') {
                steps {
                    script {
                        runGate('Validate Base Images') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
# Every FROM image must be pinned by digest (scratch and earlier build stages excepted).
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
                }
            }

            stage('Build') {
                agent {
                    docker {
                        image P.mavenImage
                        reuseNode true
                    }
                }
                stages {
                    stage('Compile') {
                        steps {
                            script {
                                runGate('Compile') {
                                    maven('clean compile', '$BUILD_DIR/compile.log')
                                }
                            }
                        }
                    }

                    stage('Unit Test') {
                        steps {
                            script {
                                runGate('Unit Test') {
                                    maven('test', '$TEST_DIR/unit-tests.log')
                                }
                            }
                        }
                        post {
                            always {
                                junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: false, keepLongStdio: true
                            }
                        }
                    }

                    stage('Package') {
                        steps {
                            script {
                                runGate('Package') {
                                    maven('package -DskipTests', '$BUILD_DIR/package.log')
                                    sh '''#!/usr/bin/env bash
set -Eeuo pipefail
find . -path '*/target/*.jar' -type f -print0 | sort -z \
  | xargs -0 --no-run-if-empty sha256sum > "$BUILD_DIR/package-checksums.sha256"
test -s "$BUILD_DIR/package-checksums.sha256"
'''
                                }
                            }
                        }
                    }
                }
            }

            stage('Security Scans') {
                agent {
                    docker {
                        image P.mavenImage
                        reuseNode true
                    }
                }
                stages {
                    stage('SAST - Polaris') {
                        steps {
                            script {
                                runGate('SAST - Polaris') {
                                    def result = polarisScanStep(
                                        application_name  : P.scanApplication,
                                        project_name      : svc.name,
                                        branch_name       : env.SCAN_REF,
                                        assessment_types  : P.polarisAssessment,
                                        test_sast_type    : P.polarisSastType,
                                        test_sast_location: P.polarisLocation)
                                    assertPassed('Polaris', result)
                                    writeJson("${env.SECURITY_DIR}/polaris/scan-reference.json",
                                              [project: svc.name, ref: env.SCAN_REF, commit: env.GIT_COMMIT,
                                               result: 'passed', completedAt: utcNow()])
                                }
                            }
                        }
                    }

                    stage('SCA - Black Duck') {
                        steps {
                            script {
                                runGate('SCA - Black Duck') {
                                    def result = blackDuckScanStep(svc.name, env.SCAN_REF, P.blackDuckGroup)
                                    assertPassed('Black Duck', result)
                                    writeJson("${env.SECURITY_DIR}/blackduck/scan-reference.json",
                                              [projectGroup: P.blackDuckGroup, project: svc.name, version: env.SCAN_REF,
                                               commit: env.GIT_COMMIT, result: 'passed', completedAt: utcNow()])
                                }
                            }
                        }
                    }
                }
            }

            stage('Build Image') {
                steps {
                    script {
                        runGate('Build Image') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
docker build --pull --no-cache \
  --file "$DOCKERFILE" \
  --label "org.opencontainers.image.revision=$GIT_COMMIT" \
  --label "org.opencontainers.image.version=$RELEASE_TAG" \
  --label "org.opencontainers.image.source=$GIT_URL" \
  --tag "$LOCAL_IMAGE" . 2>&1 | tee "$BUILD_DIR/image-build.log"
docker image inspect --format '{{.Id}}' "$LOCAL_IMAGE" > "$BUILD_DIR/local-image-id.txt"
'''
                        }
                    }
                }
            }

            stage('Scan Image') {
                steps {
                    script {
                        runGate('Scan Image') {
                            // Fixed policy: TRIVY_SEVERITY / TRIVY_IGNORE_UNFIXED cannot be changed by the service repo.
                            withEnv(["TRIVY_SEVERITY=${P.trivySeverity}", "TRIVY_IGNORE_UNFIXED=${P.trivyIgnoreUnfixed}"]) {
                                sh '''#!/usr/bin/env bash
set -Eeuo pipefail
trivy image --exit-code 1 --format json --output "$SECURITY_DIR/image-scan.json" "$LOCAL_IMAGE"
if [ -n "${TAG_NAME:-}" ]; then
  # Same result in cosign-vuln format, attested to the release digest later.
  trivy image --skip-db-update --exit-code 0 --format cosign-vuln \
    --output "$SECURITY_DIR/image-vuln.json" "$LOCAL_IMAGE"
fi
'''
                            }
                        }
                    }
                }
            }

            stage('Login to Harbor') {
                when { buildingTag() }
                steps {
                    script {
                        runGate('Login to Harbor') { registryLogin(P, 'docker') }
                    }
                }
            }

            stage('Push Image') {
                when { buildingTag() }
                steps {
                    script {
                        runGate('Push Image') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
export DOCKER_CONFIG="$WORKSPACE/.docker"
docker tag "$LOCAL_IMAGE" "$REMOTE_IMAGE_TAG"
docker push "$REMOTE_IMAGE_TAG" 2>&1 | tee "$BUILD_DIR/image-push.log"
'''
                        }
                    }
                }
            }

            stage('Resolve Immutable Image Digest') {
                when { buildingTag() }
                steps {
                    script {
                        runGate('Resolve Immutable Image Digest') {
                            // Read the pushed digest from the local image: no re-pull by mutable tag.
                            env.IMAGE_DIGEST_REF = sh(returnStdout: true, script: '''#!/usr/bin/env bash
set -Eeuo pipefail
docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$LOCAL_IMAGE" \
  | grep "^$IMAGE_REPOSITORY@sha256:"
''').trim()
                            if (!(env.IMAGE_DIGEST_REF ==~ /^[^@\s]+@sha256:[a-f0-9]{64}$/) ||
                                !env.IMAGE_DIGEST_REF.startsWith("${env.IMAGE_REPOSITORY}@sha256:")) {
                                error("Expected exactly one digest in ${env.IMAGE_REPOSITORY}, got: ${env.IMAGE_DIGEST_REF}")
                            }
                            env.IMAGE_DIGEST = env.IMAGE_DIGEST_REF.split('@', 2)[1]
                            writeFile file: "${env.EVIDENCE_DIR}/image-digest-reference.txt", text: "${env.IMAGE_DIGEST_REF}\n"
                            echo "Immutable OT release image: ${env.IMAGE_DIGEST_REF}"
                        }
                    }
                }
            }

            stage('Generate SBOM') {
                when { buildingTag() }
                steps {
                    script {
                        runGate('Generate SBOM') {
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
export DOCKER_CONFIG="$WORKSPACE/.docker"
syft scan "registry:$IMAGE_DIGEST_REF" --output "cyclonedx-json=$SBOM_DIR/sbom.cdx.json" \
  2>&1 | tee "$SBOM_DIR/syft.log"
test -s "$SBOM_DIR/sbom.cdx.json"
'''
                        }
                    }
                    stash name: 'attest-inputs', includes: 'evidence/sbom/sbom.cdx.json,evidence/security/image-vuln.json,evidence/security/image-scan.json'
                }
            }

            // Signing runs on a separate agent that only receives the evidence files, never source or build tools.
            stage('Sign & Attest') {
                when {
                    beforeAgent true
                    buildingTag()
                }
                agent { label P.signAgent }
                stages {
                    stage('Prepare Signer') {
                        steps {
                            deleteDir()
                            unstash 'attest-inputs'
                            script {
                                runGate('Prepare Signer') {
                                    registryLogin(P, 'cosign')
                                    // Fail early if the private key does not match the pinned public key.
                                    withSigningKey(P) {
                                        sh '''#!/usr/bin/env bash
set -Eeuo pipefail
derived="$(mktemp)"; trap 'rm -f "$derived"' EXIT
cosign public-key --key "$COSIGN_KEY" > "$derived"
cmp -s <(openssl pkey -pubin -in "$derived" -outform DER) <(openssl pkey -pubin -in "$COSIGN_PUB" -outform DER) \
  || { echo 'COSIGN_PRIVATE_KEY does not match COSIGN_PUBLIC_KEY' >&2; exit 1; }
cp "$COSIGN_PUB" "$SECURITY_DIR/cosign.pub"
sha256sum "$SECURITY_DIR/cosign.pub" > "$SECURITY_DIR/cosign.pub.sha256"
'''
                                    }
                                }
                            }
                        }
                    }

                    stage('Sign Image Digest') {
                        steps {
                            script {
                                runGate('Sign Image Digest') {
                                    withSigningKey(P) {
                                        sh '''#!/usr/bin/env bash
set -Eeuo pipefail
cosign sign $COSIGN_FLAGS --key "$COSIGN_KEY" "$IMAGE_DIGEST_REF" 2>&1 | tee "$SECURITY_DIR/cosign-sign.log"
'''
                                    }
                                }
                            }
                        }
                    }

                    stage('Attest SBOM') {
                        steps {
                            script {
                                runGate('Attest SBOM') { attest(P, 'cyclonedx', "${env.SBOM_DIR}/sbom.cdx.json") }
                            }
                        }
                    }

                    stage('Attest Vulnerability Scan') {
                        steps {
                            script {
                                runGate('Attest Vulnerability Scan') { attest(P, 'vuln', "${env.SECURITY_DIR}/image-vuln.json") }
                            }
                        }
                    }

                    stage('Attest Build Provenance') {
                        steps {
                            script {
                                runGate('Attest Build Provenance') {
                                    String file = "${env.SECURITY_DIR}/slsa-provenance.json"
                                    writeJson(file, provenance(P, svc))
                                    attest(P, 'slsaprovenance1', file)   // SLSA v1 predicate
                                }
                            }
                        }
                    }

                    stage('Create OT Release Manifest') {
                        steps {
                            script {
                                runGate('Create OT Release Manifest') {
                                    String file = "${env.EVIDENCE_DIR}/ot-release.json"
                                    writeJson(file, releaseManifest(P, svc))
                                    attest(P, P.releaseRecordType, file)   // signed, attached to the digest
                                }
                            }
                        }
                    }

                    stage('Verify Release Evidence') {
                        steps {
                            script {
                                runGate('Verify Release Evidence') {
                                    withVerifyKey(P) {
                                        withEnv(["RECORD_TYPE=${P.releaseRecordType}"]) {
                                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
verify=(--key "$COSIGN_PUB" --insecure-ignore-tlog=true)

cosign verify "${verify[@]}" "$IMAGE_DIGEST_REF" > "$SECURITY_DIR/image-signature-verification.json"
for type in cyclonedx vuln slsaprovenance1 "$RECORD_TYPE"; do
  cosign verify-attestation "${verify[@]}" --type "$type" "$IMAGE_DIGEST_REF" \
    > "$SECURITY_DIR/attestation-verification-${type//[^a-z0-9]/_}.json"
done

for f in "$SECURITY_DIR/image-scan.json" "$SBOM_DIR/sbom.cdx.json" \
         "$SECURITY_DIR/slsa-provenance.json" "$EVIDENCE_DIR/ot-release.json"; do
  test -s "$f" || { echo "Required release evidence is missing: $f" >&2; exit 1; }
done
'''
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        sh 'rm -rf "$WORKSPACE/.docker"'
                        stash name: 'sign-evidence', includes: 'evidence/**', allowEmpty: true
                    }
                }
            }

            stage('Publish Evidence') {
                steps {
                    script {
                        runGate('Publish Evidence') {
                            if (env.TAG_NAME) {
                                unstash 'sign-evidence'
                            }
                            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
find "$EVIDENCE_DIR" -type f ! -name evidence-checksums.sha256 -print0 | sort -z \
  | xargs -0 --no-run-if-empty sha256sum > "$EVIDENCE_DIR/evidence-checksums.sha256"
'''
                            archiveArtifacts artifacts: 'evidence/**', fingerprint: true, allowEmptyArchive: false
                            if (env.TAG_NAME) {
                                currentBuild.keepLog = true   // release evidence is never rotated away
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    notifyTeams(P, 'SUCCESS', env.TAG_NAME ? 'OT release verified and ready for promotion'
                                                           : 'Development CI passed; nothing was published')
                }
            }
            unsuccessful {
                script {
                    archiveArtifacts artifacts: 'evidence/**', allowEmptyArchive: true
                    notifyTeams(P, currentBuild.currentResult, "OT CI stopped at: ${env.FAILED_STAGE ?: env.CURRENT_GATE ?: 'unknown'}")
                }
            }
            always {
                sh '''#!/usr/bin/env bash
rm -rf "$WORKSPACE/.docker"
docker image rm "${LOCAL_IMAGE:-none}" "${REMOTE_IMAGE_TAG:-none}" >/dev/null 2>&1 || true
'''
                cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

Map validateConfig(Map config) {
    for (key in config.keySet()) {
        if (!(key in ['serviceName', 'dockerfile'])) {
            error("Unsupported otReleasePipeline setting '${key}': security settings are fixed in the shared library")
        }
    }
    String name = config.serviceName?.toString()?.trim()
    if (!(name ==~ /^[a-z0-9][a-z0-9-]{1,62}$/)) {
        error("serviceName must be lowercase letters, digits and hyphens: '${name}'")
    }
    String dockerfile = (config.dockerfile ?: 'Dockerfile').toString()
    if (!(dockerfile ==~ /^[A-Za-z0-9._\/-]+$/) || dockerfile.contains('..') || dockerfile.startsWith('/')) {
        error("dockerfile must be a relative path inside the repository: '${dockerfile}'")
    }
    if (!(POLICY.mavenImage ==~ /^[^@]+@sha256:[a-f0-9]{64}$/)) {
        error('Shared library POLICY is incomplete: set the mavenImage digest')
    }
    return [name      : name,
            dockerfile: dockerfile,
            repository: "${POLICY.registry}/${POLICY.registryProject}/${name}".toString()]
}

// Runs one gate and records PASSED/FAILED evidence for it.
void runGate(String gateName, Closure body) {
    env.CURRENT_GATE = gateName
    String startedAt = utcNow()
    try {
        body()
        writeGateRecord(gateName, 'PASSED', startedAt, '')
    } catch (Exception failure) {
        env.FAILED_STAGE = gateName
        writeGateRecord(gateName, 'FAILED', startedAt, sanitize(failure.getMessage()))
        throw failure
    }
}

void writeGateRecord(String gateName, String result, String startedAt, String message) {
    String safeName = gateName.toLowerCase().replaceAll('[^a-z0-9]+', '-').replaceAll('^-|-$', '')
    writeJson("${env.EVIDENCE_DIR ?: 'evidence'}/gates/${safeName}.json",
              [gate: gateName, result: result, startedAt: startedAt, completedAt: utcNow(),
               job: env.JOB_NAME, buildNumber: env.BUILD_NUMBER, releaseTag: env.RELEASE_TAG ?: '',
               gitCommit: env.GIT_COMMIT ?: '', imageReference: env.IMAGE_DIGEST_REF ?: '',
               failureMessage: message])
}

void maven(String goals, String logFile) {
    configFileProvider([configFile(fileId: POLICY.mavenSettings, variable: 'MAVEN_SETTINGS')]) {
        // Per-build local repository: no Maven cache shared between builds or trust levels.
        sh """#!/usr/bin/env bash
set -Eeuo pipefail
mvn -B -ntp -s "\$MAVEN_SETTINGS" -Dmaven.repo.local="\$WORKSPACE/.m2/repository" ${goals} 2>&1 | tee "${logFile}"
"""
    }
}

// Fail closed: only an explicit pass counts. The existing scanner steps throw on failure and return
// null on success, so null is a pass.
void assertPassed(String scanner, def result) {
    if (result == null || result == true) {
        return
    }
    String value = result.toString().trim().toLowerCase()
    if (!(value in ['success', 'passed', 'ok'])) {
        error("${scanner} security policy gate failed: ${sanitize(value)}")
    }
}

// Logs in to Harbor into a per-build config dir; the password is only in scope for the login itself.
void registryLogin(Map P, String cli) {
    withCredentials([usernamePassword(credentialsId: P.harborCredentials,
                                      usernameVariable: 'HARBOR_USERNAME',
                                      passwordVariable: 'HARBOR_PASSWORD')]) {
        withEnv(["REGISTRY_CLI=${cli}"]) {
            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
export DOCKER_CONFIG="$WORKSPACE/.docker"
mkdir -p "$DOCKER_CONFIG"
printf '%s' "$HARBOR_PASSWORD" \
  | "$REGISTRY_CLI" login "$HARBOR_REGISTRY" --username "$HARBOR_USERNAME" --password-stdin >/dev/null
'''
        }
    }
}

// Key file, password and public key are only in scope for the step; Jenkins writes the key to a
// temporary file outside the workspace and deletes it when the block ends. cosign reads COSIGN_PASSWORD.
void withSigningKey(Map P, Closure body) {
    withCredentials([file(credentialsId: P.cosignPrivateKey, variable: 'COSIGN_KEY'),
                     string(credentialsId: P.cosignPassword, variable: 'COSIGN_PASSWORD'),
                     file(credentialsId: P.cosignPublicKey, variable: 'COSIGN_PUB')]) {
        withEnv(["DOCKER_CONFIG=${env.WORKSPACE}/.docker",
                 'COSIGN_FLAGS=--yes --tlog-upload=false --new-bundle-format=false --use-signing-config=false']) {
            body()
        }
    }
}

// Verification needs the public key only.
void withVerifyKey(Map P, Closure body) {
    withCredentials([file(credentialsId: P.cosignPublicKey, variable: 'COSIGN_PUB')]) {
        withEnv(["DOCKER_CONFIG=${env.WORKSPACE}/.docker"]) {
            body()
        }
    }
}

void attest(Map P, String type, String predicate) {
    withSigningKey(P) {
        withEnv(["ATTEST_TYPE=${type}", "PREDICATE=${predicate}"]) {
            sh '''#!/usr/bin/env bash
set -Eeuo pipefail
test -s "$PREDICATE"
cosign attest $COSIGN_FLAGS --key "$COSIGN_KEY" --type "$ATTEST_TYPE" --predicate "$PREDICATE" "$IMAGE_DIGEST_REF" \
  2>&1 | tee "$SECURITY_DIR/cosign-attest-${ATTEST_TYPE//[^a-z0-9]/_}.log"
'''
        }
    }
}

// SLSA v1 provenance (cosign --type slsaprovenance1).
Map provenance(Map P, Map svc) {
    return [
        buildDefinition: [
            buildType           : P.buildType,
            externalParameters  : [repository: env.GIT_URL, ref: "refs/tags/${env.TAG_NAME}".toString(),
                                   service: svc.name, imageRepository: svc.repository],
            internalParameters  : [jenkinsJob: env.JOB_NAME, jenkinsBuildNumber: env.BUILD_NUMBER],
            resolvedDependencies: [
                [uri: "git+${env.GIT_URL}@refs/tags/${env.TAG_NAME}".toString(), digest: [gitCommit: env.GIT_COMMIT]],
                [uri: "oci://${P.mavenImage.split('@')[0]}".toString(), digest: [sha256: P.mavenImage.split('@sha256:')[1]]]
            ]
        ],
        runDetails     : [
            builder : [id: P.builderId],
            metadata: [invocationId: env.BUILD_URL,
                       startedOn   : new Date(currentBuild.startTimeInMillis).format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))]
        ]
    ]
}

// Release manifest: what was released and which gates it passed. Signed and attached to the digest.
Map releaseManifest(Map P, Map svc) {
    return [
        product       : P.product,
        service       : svc.name,
        releaseTag    : env.TAG_NAME,
        gitCommit     : env.GIT_COMMIT,
        gitUrl        : env.GIT_URL,
        imageReference: env.IMAGE_DIGEST_REF,
        imageDigest   : env.IMAGE_DIGEST,
        gates         : [tagSignature: 'verified', unitTests: 'passed', sast: 'passed', sca: 'passed',
                         imageScan   : "passed (${P.trivySeverity}, ignoreUnfixed=${P.trivyIgnoreUnfixed})".toString()],
        jenkins       : [job: env.JOB_NAME, build: env.BUILD_NUMBER, url: env.BUILD_URL],
        createdAt     : utcNow(),
        releaseStatus : 'verified'
    ]
}

void writeJson(String file, Map content) {
    writeFile file: file, text: JsonOutput.prettyPrint(JsonOutput.toJson(content)) + '\n'
}

String utcNow() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
}

String sanitize(String message) {
    if (!message) {
        return ''
    }
    return message
        .replaceAll('(?i)(password|token|authorization)[=:]\\S+', '$1=***')
        .replaceAll('(?i)bearer\\s+\\S+', 'Bearer ***')
        .take(500)
}

void notifyTeams(Map P, String status, String summary) {
    try {
        office365ConnectorSend(
            credentialsId: P.teamsWebhook,
            adaptiveCards: true,
            color        : status == 'SUCCESS' ? '2EB886' : 'D13438',
            status       : status,
            message      : """**Converto OT CI Release**
- **Status:** ${status}
- **Summary:** ${summary}
- **Release:** ${env.RELEASE_TAG ?: 'unknown'}
- **Service:** ${env.SERVICE_NAME ?: 'unknown'}
- **Commit:** ${env.GIT_COMMIT ?: 'unknown'}
- **Image:** ${env.IMAGE_DIGEST_REF ?: 'not published'}
- **Build:** ${env.BUILD_URL}""")
    } catch (Exception e) {
        echo "WARNING: Teams notification failed (${e.getClass().getSimpleName()})"
    }
}
