package com.tetrapak.ot

/**
 * Central release policy for the OT release pipeline.
 *
 * Service repositories cannot override anything here. Changes are reviewed by the
 * platform/security owners (CODEOWNERS on this file) and released as a new library tag.
 * Values marked SET_ME are environment-specific; the pipeline fails until they are set.
 */
class ReleasePolicy implements Serializable {

    static final Map VALUES = [
        product                  : 'converto',

        // IT Harbor (Trusted Registries promotes from here). Robot account: push + accessories,
        // limited to registryProject. Tag immutability must be enabled on the project.
        registry                 : 'hub.tetrapak.com',
        registryProject          : 'converto-harbor',
        harborCredentialsId      : 'OT_RELEASE_HARBOR_ROBOT',

        // Dedicated, ephemeral agents. The sign agent runs no build tools and never sees source code.
        buildAgent               : 'ot-release-build',
        signAgent                : 'ot-release-sign',

        // Release trigger: signed annotated tag on the release branch.
        releaseBranch            : 'master',
        releaseTagPattern        : '^ot-release-[0-9]{4}\\.[0-9]{2}\\.[0-9]+$',
        releaseSignersResource   : 'ot/release-signers.asc',   // public keys allowed to sign release tags

        // Build toolchain: builder image pinned by digest; Maven resolves only through the Nexus mirror.
        mavenImage               : 'hub.tetrapak.com/shared-services/maven-docker-cli@sha256:SET_ME',
        mavenSettingsId          : 'converto-maven-settings',  // Config File Provider: settings.xml with Nexus mirror

        // Gates
        trivy                    : [severity: 'HIGH,CRITICAL', ignoreUnfixed: 'true'],
        scan                     : [application        : 'Converto',
                                    blackDuckGroup     : 'Converto',
                                    polarisAssessment  : 'SAST',
                                    polarisSastType    : 'SAST-FULL',
                                    polarisSastLocation: 'local'],

        // Signing: non-exportable key in Vault Transit. The token may only call sign on this key
        // (Vault policy) and should be short-lived and bound to the sign agents.
        signing                  : [keyRef                 : 'hashivault://converto-ot-release',
                                    vaultAddr              : 'SET_ME',
                                    vaultTokenCredentialsId: 'OT_RELEASE_VAULT_SIGNER'],

        provenance               : [buildType: 'urn:tetrapak:build-type:jenkins:converto-ot-release:v1',
                                    builderId: 'urn:tetrapak:builder:jenkins:converto-ot-ci:v1'],
        releaseRecordType        : 'urn:tetrapak:ot:release-record:v1',

        teamsWebhookCredentialsId: 'OT_CICD_TEAMS_WEBHOOK',
    ].asImmutable()
}
