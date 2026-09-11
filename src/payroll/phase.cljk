(ns payroll.phase
  "Phase 0→3 staged rollout — this actor's analog of `cloud-itonami-
  isic-6311`'s rollout phases: start narrow (read-only), widen as trust
  grows. Where the PayrollGovernor answers 'is this allowed?', the phase
  answers 'how much autonomy does the actor have *yet*?'. It can only ever
  make the actor MORE conservative than the governor.

    Phase 0  read-only        — `:disclosure/query` only (still governed).
    Phase 1  assisted-payroll — `:payroll/process` allowed, every run needs
                                human approval.
    Phase 2  + filing         — adds `:filing/submit` and `:dispute/request`
                                (still approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence
                                `:payroll/process`/`:filing/submit` may
                                auto-commit.

  `:dispute/request` is deliberately NEVER a member of any phase's `:auto`
  set, at any phase — a payroll dispute always reaches a human.")

(def read-ops  #{:disclosure/query})
(def write-ops #{:payroll/process :filing/submit :dispute/request})

(def phases
  {0 {:label "read-only"        :writes #{}
                                 :auto #{}}
   1 {:label "assisted-payroll" :writes #{:payroll/process}
                                 :auto #{}}
   2 {:label "assisted-filing"  :writes #{:payroll/process :filing/submit :dispute/request}
                                 :auto #{}}
   3 {:label "supervised-auto"  :writes #{:payroll/process :filing/submit :dispute/request}
                                 :auto #{:payroll/process :filing/submit}}})

(def default-phase
  "The phase used when `context` carries no :phase at all -- a caller that
  forgets :phase MUST get the conservative default, not maximum autonomy
  (the fail-open bug found and fixed this session in `talent.phase` and
  `cloud-itonami-isic-6311`'s own phase.cljc; this actor never shipped
  that bug in the first place)."
  1)

(defn gate
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
