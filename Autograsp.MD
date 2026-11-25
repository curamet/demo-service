**Bottom line:** AutoGRASP is a host-level OT hardening and installation tool, not a container-platform control. It still affects us in three ways: the mandate for new OT installations may cover our runtime nodes, it doesn't fit immutable K8s node lifecycles well, and our FOTIS threat models should reference it.

**What was announced**
- A semi-automated tool for OT Security installation (config, security controls, system hardening) of new OT systems, for both supplier and Tetra Pak systems.
- It's available globally in Software Center from today, 25 Nov. Software Center suggests an operator-run tool for Windows endpoints, which I can't confirm from the email.
- The installation manual TeM-3342621-0121 has been updated. All new installations should use AutoGRASP. FOTIS owns it and provides hypercare, and the old manual process stays allowed during a transition period.

**Impact on our containerized runtime work**
- **Scope risk:** The rule that all new installations use AutoGRASP could cover K8s-in-OT nodes, Podman hosts, the site OCI registry host, the approval service and the OT-side RabbitMQ nodes. If it doesn't support those Linux/VKS hosts, we need a documented equivalent mapped to the same controls, such as CIS benchmarks or OpenSCAP, approved by FOTIS.
- **Immutable nodes vs install-time hardening:** VKS (and OpenShift) node pools get rebuilt on every upgrade or scale event, which wipes per-host semi-manual hardening. For K8s, the controls belong in the node image or cluster class (or MachineConfig on OpenShift). Podman/Quadlet hosts are mutable, so AutoGRASP could fit there.
- **Conflict testing:** Hardening profiles often break container runtimes, for example by disabling IP forwarding, changing firewall rules, enforcing SELinux or tightening kernel parameters. Validate on a reference node before the golden path says "AutoGRASP + K8s/Podman".
- **Threat models (CI/CD for OT, Message Federation):** FOTIS reviews both. Update the host-hardening control references to the new TeM revision before final review, or reviewers will flag it.
- **Supplier systems:** AutoGRASP hardens the host only. Image signing, scanning, SBOMs and registry gating in our pipeline stay necessary for supplier-delivered containers.
- **Platform operating model:** Add a line for who runs AutoGRASP or the equivalent baseline on OT runtime nodes (platform team or site OT) to the IBE/GIM proposal.

**Questions to put to FOTIS**
1. Are Linux K8s and Podman hosts in scope? Is there a supported equivalent path for immutable or image-based nodes?
2. Is there a headless/CLI mode or a machine-readable control baseline we can put into IaC or the pipeline?
3. What is the compliance evidence format (report, attestation) that our OT approver gate should check?

**Actions**
- Read the updated TeM section on hardening scope.
- Send FOTIS the three questions (they asked for feedback).
- Update the control references in both threat models.
- Add a host-baseline section to the golden path.
- Add a responsibility line to the platform proposal.

Note: AutoGRASP is internal to Tetra Pak, so this assessment rests only on the email. Scope details need confirming against the TeM manual.

Want me to draft the questions email to FOTIS?


Subject: RE: Release of AutoGRASP

Hi Mikael,

Thanks for sharing, this is relevant for us. A quick take on how it connects to our work:

- Converto: the OT-side hosts we run (incl. RabbitMQ nodes) may need to follow the AutoGRASP hardening baseline for new installations.
- CI/CD for OT: the Kubernetes and Podman hosts, plus the site registry, are new OT installations, so we need to check whether AutoGRASP covers them and how it fits with nodes that get rebuilt on upgrades.
- Message Federation IT-OT: we'll reference AutoGRASP and the updated installation manual in the threat model and risk assessment.

We'll highlight this and discuss it with the FOTIS team to confirm scope and how it applies to containerized runtimes.

Best regards,
