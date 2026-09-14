# CI/CD 01 - Business &amp; Technical Requirements

> **Epic:** CI/CD Pipelines for OT · **Product Owner:** Mikael Hansson · **Lead Programme:** Factory of the Future
> **Status:** Draft for Design phase · **Related:** CI Current State · CD Current State · Epic Charter
>
> Format follows MF 03 - Business Requirements. Requirements are stated as outcomes; the technical requirements below express how each is met. Open items are tracked in the gaps register at the end.

---

## 1. Business Requirements

| BR ID | Requirement | Description |
|---|---|---|
| CICD BR 001 | Containerized applications can be deployed into the OT network | Converto must be deployable into the OT/PM network so that execution-critical MES components run in a secure factory network, isolated from the more open IT network. |
| CICD BR 002 | Deployment is automated, not manual | Deployment of large systems like Converto in Kubernetes cannot be achieved by manual handling. The solution must minimise manual effort across build, promotion and deployment. |
| CICD BR 003 | OT retains authority over deployment | OT must control when, where and in what scope a deployment occurs. Central pipelines may define what should be deployed but must not directly enforce it in OT. |
| CICD BR 004 | Deployment scope is contained per site | It must not be possible to propagate a change simultaneously and without containment across multiple factories. Deployment scope must be limited and explicit. |
| CICD BR 005 | OT operation and recovery do not depend on IT availability | OT systems must be able to operate, restart and recover without depending on the availability of central IT pipelines or IT-hosted services. |
| CICD BR 006 | Only approved, verified artifacts may run in OT | Only images that are scanned, signed and admitted to the trusted OT registry may be deployed. Unverified artifacts must be rejected. |
| CICD BR 007 | The solution is OT security compliant | The full solution must comply with the OT Security Standard and FNC design principles, and must be formally approved by FOTIS before use in the OT network. |
| CICD BR 008 | Both Kubernetes and Podman deployment are supported | The solution must support deployment processes for Kubernetes and for Podman, as both runtimes are required in OT. |
| CICD BR 009 | The pattern is generalized and reusable | The pattern must be general enough to serve as a reference for other modern containerized development in OT. Converto and P2 are the proving case, not the only consumer. |
| CICD BR 010 | Deployments are traceable and auditable to OT | It must be possible to determine what is running in any OT site, which source commit produced it, who approved it and when. The pipeline must not act as a hidden or indirect control mechanism. |
| CICD BR 011 | OT can halt and reverse a deployment | OT must be able to stop, reject or reverse a deployment locally, without routing the action through IT. |
| CICD BR 012 | Necessary Converto components are approved for use in OT | The components required in OT must be identified and analysed together with FOTIS so they are permitted to run in an OT Kubernetes cluster. |
| CICD BR 013 | Infrastructure and process exist to deploy in OT | This epic depends on other epics to provide the OT foundation: **Kubernetes in OT**, **Trusted Registries in OT** and **Authentication in OT** are mandatory. This epic must align its needs with those epics. |
| CICD BR 014 | Reference implementation is validated at one or more reference sites | Identify pilot and end reference factories, and identify what must be prepared to make validation possible. |
| CICD BR 015 | Reduced effort for subsequent workloads | The pattern must measurably reduce the work required to take a new containerized OT workload to production, compared with doing so without it. |

---

## 2. Technical Requirements

### 2.1 CI - within the IT network

| TR ID | Requirement | Description |
|---|---|---|
| CICD TR 001 | CI executes entirely within the IT network | Build, test and image production run in IT. CI holds no OT credentials and performs no OT operations. Supports BR 003. |
| CICD TR 002 | CI produces OCI-compliant images for both runtimes | Image output must be consumable by both Kubernetes and Podman. Supports BR 008. |
| CICD TR 003 | Every image is vulnerability-scanned before publication | Scanning is a mandatory gate: a failed scan must prevent publication, not merely report. Supports BR 006. |
| CICD TR 004 | Every image is cryptographically signed | Signing occurs in IT using a managed key. Signature must be verifiable independently in OT. Supports BR 006. |
| CICD TR 005 | An SBOM is generated and associated with each image | Required for provenance and for OT admission review. Supports BR 006, BR 010. |
| CICD TR 006 | Image tags are immutable | A given tag must always resolve to the same digest, in both IT and OT registries. Supports BR 010. |
| CICD TR 007 | Source commit and pipeline run are recorded as image metadata | Enables end-to-end traceability once the image crosses the boundary. Supports BR 010. |
| CICD TR 008 | Container builds run without privileged access | Rootless or daemonless build, with no Docker socket mount. Required for OT acceptability of the artifact chain. |
| CICD TR 009 | Only release-tagged commits are promotable to production | One release tag per repo commit; production deployment is possible only from release-tagged commits. Supports BR 004, BR 010. |
| CICD TR 010 | CI publishes to the IT registry only | CI has no write path into OT. Promotion into OT is performed by the trusted registry mechanism, not by CI. Supports BR 003. |

### 2.2 Artifact handoff - IT to OT

| TR ID | Requirement | Description |
|---|---|---|
| CICD TR 011 | The trusted OT registry is the sole source of images for OT | No OT workload may pull an image from an IT-hosted registry. Supports BR 005, BR 006. |
| CICD TR 012 | Signature verification occurs on admission to OT | The OT registry or admission controller verifies the IT signature against a trust anchor held in OT. Unsigned or unverifiable images are rejected. Supports BR 006. |
| CICD TR 013 | No direct traffic between the Enterprise network and the OT network | All artifact movement complies with FNC design principles; connections must not bypass the FNC firewall. Supports BR 007. |
| CICD TR 014 | Desired state (Helm charts and environment config) reaches OT by an approved path | Charts and deployment configuration must be available OT-side without a direct Enterprise-to-OT pull. Mechanism to be designed. Supports BR 005, BR 007. |
| CICD TR 015 | Artifact transfer requires no removable media | USB and other removable media are prohibited by the OT Security Golden Rules. Supports BR 007. |

### 2.3 CD - within the OT network

| TR ID | Requirement | Description |
|---|---|---|
| CICD TR 016 | Deployment into OT clusters is pull-based | OT clusters or agents retrieve approved artifacts and desired state; nothing is pushed into OT from IT. Supports BR 003, BR 005. |
| CICD TR 017 | The GitOps controller runs inside the OT network | The reconciliation engine is OT-resident and holds the only cluster deployment credentials. Supports BR 003, BR 005. |
| CICD TR 018 | An OT-controlled approval gate precedes deployment | Deployment proceeds only after an approval granted under OT governance. Scope, approver role and mechanism to be defined. Supports BR 003. |
| CICD TR 019 | Deployment scope is bounded to a single site | No OT control plane, registry or pipeline component spans multiple OT networks. Supports BR 004 and FNC principle: no direct communication between OT networks. |
| CICD TR 020 | Desired state is held authoritatively OT-side | An OT site must be able to reconcile from locally held state with no IT connectivity. Supports BR 005. |
| CICD TR 021 | A Podman deployment mechanism is defined | Desired-state expression, reconciliation, approval and rollback must be defined for Podman workloads, which are not covered by a Kubernetes GitOps controller. Supports BR 008. |
| CICD TR 022 | Local halt and rollback capability exists in OT | OT can stop an in-progress deployment and revert to the previous known-good state without IT involvement. Supports BR 011. |
| CICD TR 023 | No pipeline identity holds broad administrative access in OT | Service identities are least-privilege and scoped to a single site and namespace. Supports BR 003, BR 007. |
| CICD TR 024 | Deployment activity is visible to OT | Approvals, deployments, sync status and drift are observable by OT operators. Supports BR 010. |
| CICD TR 025 | Authentication and authorisation integrate with Authentication in OT | Identity for operators and service accounts is provided by the OT authentication capability, not by IT-hosted identity. Supports BR 007, BR 013. |

### 2.4 Platform capabilities required from OT

| TR ID | Requirement | Description |
|---|---|---|
| CICD TR 026 | Persistent storage for stateful workloads | A storage class supporting one PostgreSQL volume per business service, per environment, per site. |
| CICD TR 027 | In-cluster message broker support | The application requires a broker for east-west service messaging. |
| CICD TR 028 | Ingress and certificate management | Automated TLS issuance and renewal; manual certificate handling is not viable in OT. |
| CICD TR 029 | Secrets management with automated rotation | An OT-local secrets store. Manual loading and rotation, as used in IT today, is not acceptable in OT. |
| CICD TR 030 | Admission policy enforcement | Policy engine enforcing image provenance, signature verification, resource limits and non-root execution. |
| CICD TR 031 | Backup and restore for cluster state and volumes | Cluster configuration, controller state and persistent volumes must be recoverable. Supports the Golden Rule on valid backups. |
| CICD TR 032 | Observability within OT | Metrics, logs and deployment status available OT-side without dependency on IT-hosted tooling. |
| CICD TR 033 | Defined patch process for OT cluster and CD components | Supports the Golden Rule on keeping OT systems updated and secure. |

### 2.5 Reference pattern deliverables

| TR ID | Requirement | Description |
|---|---|---|
| CICD TR 034 | Scaffold or template repository | A reusable starting point for a new OT containerized workload, equivalent in role to new-env-template. Supports BR 009. |
| CICD TR 035 | Reference Helm chart set and desired-state structure | Standard structure for OT environments, reusable beyond Converto. Supports BR 009. |
| CICD TR 036 | Pre-approved network and security pattern | A documented pattern already reviewed with FOTIS, so a subsequent team inherits approval rather than re-seeking it. Supports BR 009, BR 015. |
| CICD TR 037 | Documented onboarding path and support model | Named owner, versioning and documentation for the pattern itself. Supports BR 009. |
| CICD TR 038 | Baseline and measurement of time-to-production | Time from "new containerized workload for OT" to "running in a factory", baselined during Analysis and measured at the reference factory. Supports BR 015. |

---

## 3. Gaps, Issues &amp; Open Questions

**Severity:** 🔴 Blocking - design cannot complete · 🟠 High - must be designed in, not retrofitted · 🟡 Medium - process or governance

| ID | Gap / Issue | Severity | Relates to | Owner | Status |
|---|---|---|---|---|---|
| CICD GAP 001 | No defined path for desired state (Helm charts, environment config) to reach OT. The trusted registry epic moves images only. Direct Enterprise-to-OT pull is prohibited by FNC. | 🔴 | TR 014, TR 020 | *(fill)* | Open |
| CICD GAP 002 | Podman deployment model undefined. No GitOps controller equivalent; desired-state expression, reconciliation, approval and rollback all unspecified. Effectively a second CD pattern. | 🔴 | TR 021, BR 008 | *(fill)* | Open |
| CICD GAP 003 | Approval model undefined - who approves, at what granularity (site / release / service), technical gate or procedural, and where the gate sits. | 🔴 | TR 018, BR 003 | FOTIS + MFS | Open |
| CICD GAP 004 | Signing and verification chain across the IT/OT boundary undefined: key management, trust anchor distribution in OT, rotation, and handling of verification failure. Spans this epic and the registry epic. | 🔴 | TR 004, TR 012 | Joint | Open |
| CICD GAP 005 | Ownership seam: image promotion from IT registry to trusted OT registry is unassigned between this epic and the Trusted Registries epic. | 🔴 | TR 010, TR 011 | Joint | Open |
| CICD GAP 006 | Kubernetes in OT not yet delivered. Nothing in the CD target can be built or validated until available. | 🔴 | BR 013 | K8s in OT epic | Dependency |
| CICD GAP 007 | Trusted Registries in OT not yet delivered. | 🔴 | BR 013 | Registry epic | Dependency |
| CICD GAP 008 | Authentication in OT not yet delivered; identity model for operators and service accounts depends on it. | 🔴 | TR 025, BR 013 | Auth in OT epic | Dependency |
| CICD GAP 009 | Per-site containment not yet reflected in the branch and environment model. A shared production branch cannot satisfy site isolation. | 🔴 | TR 019, BR 004 | MFS | Open |
| CICD GAP 010 | OT recovery with no IT connectivity is undemonstrated. Requires OT-authoritative desired state and registry, plus a documented recovery procedure. | 🟠 | TR 020, BR 005 | *(fill)* | Open |
| CICD GAP 011 | Deploy Tool disposition undecided - OT variant, split IT/OT role, or replacement. Workstation-initiated production deploys from IT are unlikely to be permissible. | 🟠 | TR 016, TR 023 | MFS | Open |
| CICD GAP 012 | Secrets loading and rotation is manual today and cannot traverse Enterprise-to-OT directly. No OT-local store designed. | 🟠 | TR 029 | *(fill)* | Open |
| CICD GAP 013 | Backup, restore and patching of OT clusters, CD components and PostgreSQL volumes not designed. | 🟠 | TR 031, TR 033 | *(fill)* | Open |
| CICD GAP 014 | Dual-runtime image production not yet implemented; both Docker and Podman targets must be built, scanned and signed consistently across a split CI toolchain. | 🟠 | TR 002, BR 008 | MFS | Open |
| CICD GAP 015 | Container build privileges unconfirmed. Privileged or Docker-socket builds are a likely OT finding. | 🟠 | TR 008 | MFS | Open |
| CICD GAP 016 | SBOM generation, image signing and provenance not confirmed as implemented or as blocking gates in current CI. | 🟠 | TR 003-005 | MFS | Open |
| CICD GAP 017 | OT-local halt and reversal capability does not exist; rollback today is a git revert on an IT-side repository. | 🟠 | TR 022, BR 011 | *(fill)* | Open |
| CICD GAP 018 | Split CI toolchain (two SCMs, two CI platforms) doubles the artifact chain to be verified and approved. Scope decision needed: support both, or standardise. | 🟠 | TR 001-010, BR 009 | MFS | Open |
| CICD GAP 019 | Converto components requiring approval for OT not yet identified or analysed with FOTIS. | 🟠 | BR 012 | MFS + FOTIS | Open |
| CICD GAP 020 | Central fleet or cluster lifecycle management spanning multiple OT networks may conflict with FNC principle on inter-OT communication. | 🟠 | TR 019, BR 007 | *(fill)* | Open |
| CICD GAP 021 | Audit visibility of deployment actions to OT operators not designed; must not constitute hidden or indirect control. | 🟡 | TR 024, BR 010 | *(fill)* | Open |
| CICD GAP 022 | RACI undefined - who approves, halts, reverses and owns each OT-side component. | 🟡 | BR 003, BR 011 | FOTIS + MFS | Open |
| CICD GAP 023 | OT Security installation approval not yet reflected as a project gate. | 🟡 | BR 007 | *(fill)* | Open |
| CICD GAP 024 | OT Security trainings (System Owners Journey, OT Security Basics) not yet scheduled; likely prerequisite for design approval. | 🟡 | BR 007 | Project team | Open |
| CICD GAP 025 | PC (Process Centre) process not mapped to project phases or gates. | 🟡 | - | *(fill)* | Open |
| CICD GAP 026 | Incident reporting path to Global Service Desk not reflected in the operational model. | 🟡 | BR 007 | *(fill)* | Open |
| CICD GAP 027 | End-to-end traceability across the registry boundary unproven - linking a running OT workload back to its IT source commit after promotion. | 🟡 | TR 007, BR 010 | *(fill)* | Open |
| CICD GAP 028 | Reference factories (pilot and end site) not yet selected; preparation requirements not identified. | 🟡 | BR 014 | Mikael Hansson | Open |
| CICD GAP 029 | Time-to-production baseline not captured, so the reduction-of-work benefit cannot be evidenced at KDP. | 🟡 | TR 038, BR 015 | *(fill)* | Open |

---

## 4. Dependency Epics

| Epic | Provides | Required for | Status |
|---|---|---|---|
| Kubernetes in OT | Runtime for containerized workloads in OT | All CD technical requirements | Ongoing |
| Trusted Registries in OT | Verified image source for OT | TR 011, TR 012 | Ongoing |
| Authentication in OT | Identity for operators and service accounts | TR 025 | Ongoing |
| Linux as a Container host | Podman host platform | TR 021 | Not a direct Converto dependency |

---

*Next: close the 🔴 items with FOTIS and the dependency epic owners before Design-phase sign-off. GAP 004 and GAP 005 are joint-ownership seams and should get named owners first - if each project assumes the other is building promotion and verification, neither will.*
