# Bank NL2SQL runtime data-source recovery

## Objective

Ensure a Bank NL2SQL runtime binds its semantic model to the same isolated H2
database into which the frozen v2.0.6 facts are imported.  A runtime must fail
closed before formal evaluation when that invariant is not true.

## Observed failure

The model `bank_metric_daily` (id `33`) is bound to database id `1`, whose
JDBC URL targets a legacy Desktop H2 path.  That target has no
`bank_metric_daily` table.  The formal smoke therefore parses every plan but
fails every execution with H2 `42102`.

## Recovery order

1. Add a reusable stopped-state binding check that compares the bank model's
   database URL with the requested isolated metadata database and verifies the
   required `bank_*` tables and fact count.  It must not accept a receipt based
   only on workbook-import counts.
2. Create a new runtime-local state from the current isolated metadata state,
   rebind the selected bank model's data source to that state, and import the
   frozen v2.0.6 H2 package before starting the service.
3. Start the candidate runtime, bootstrap the Agent, and run the official
   smoke.  Continue to dev only after smoke is 5/5.
4. After the candidate has passed smoke and no process references the legacy
   path, remove only the active legacy `semantic.mv.db` and its trace sidecar.
   Historical `*.bak` recovery files remain out of scope.

## Protection and rollback

- Never modify frozen dataset files, gold records, scoring code, or the
  existing legacy database before the isolated runtime passes smoke.
- Do not delete historical backup files as part of this recovery.
- If validation fails, stop before legacy cleanup; the untouched legacy H2
  file remains the rollback artifact.

## Success evidence

- The selected model's database id resolves to the new isolated H2 JDBC URL.
- `bank_organization=13`, `bank_metric_definition=21`, and
  `bank_metric_daily=132678` in that same H2 file.
- The official smoke report is 5/5 under the unchanged Fact v3 protocol.
- The old active H2 file is absent only after the preceding evidence exists.
