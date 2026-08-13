---
name: add-preset-config
description: Add, import, or update Three Body Lab built-in simulation presets from JSON configuration files, including preset keys, Java core definitions, OpenAPI enums/examples, frontend generated types, mock data, default selection behavior, and regression tests. Use when a user asks to add configurations or schemes to the built-in preset selector, import an exported Three Body Lab config, extend A/B/C-style preset keys, or change which preset is selected by default in this repository.
---

# Add Preset Config

Add configurations as one consistent feature across the core, contract, live frontend, and mock frontend. Preserve physical values exactly unless the user explicitly requests a transformation.

## Establish scope

1. Read the repository `AGENTS.md` and inspect `git status --short`.
2. Read the supplied configuration file completely. Accept a bare `SimulationConfig`, an export wrapper containing `config`, an array of either form, or multiple comma-separated export objects. Do not rewrite the source file unless requested.
3. Confirm the mapping between source configurations and preset keys from the request. If keys are unspecified, use the next unused sequential keys and state the mapping before editing.
4. Treat changing the default selection as separate behavior. Only change it when requested.

## Inspect the preset chain

Read these files and their directly related tests before editing:

- `simulation-core/src/main/java/com/threebody/core/PresetKey.java`
- `simulation-core/src/main/java/com/threebody/core/Presets.java`
- `contracts/openapi.yaml`
- `contracts/examples/presets.json`
- `frontend/src/contracts/index.ts`
- `frontend/src/mocks/mockRepository.ts`
- `frontend/src/stores/draft.ts`
- `frontend/src/components/ParameterEditor.vue`

Search for assumptions about the old key range or preset count. Do not edit generated files manually.

## Preserve configuration semantics

- Keep mass, position, velocity, time step, gravitational constant, softening length, maximum steps, and target time in SI units.
- Preserve body order, IDs, names, colors, signs, decimal precision, and three-dimensional coordinates.
- Do not infer missing physics, rebalance velocities, rename bodies, or tune stability without explicit authorization.
- Normalize only the preset display name and description needed to fit the selected key.
- Check body count limits, unique IDs, finite vectors, positive masses and time step, valid colors, nonnegative softening, and a valid end condition.
- Compare the resulting fixture data programmatically with the source data, excluding only fields intentionally renamed.

## Implement consistently

1. Extend `PresetKey` and update both `Presets.all()` ordering and `Presets.byKey()` exhaustively.
2. Add a focused preset factory in `Presets`; reuse nearby helpers and avoid dependencies or filesystem access in `simulation-core`.
3. Update the `/presets` OpenAPI summary, preset key enum, and `contracts/examples/presets.json`. Keep the example order identical to `Presets.all()`.
4. Run `npm.cmd run generate:contracts` from `frontend/`; review the generated diff and never hand-edit `frontend/src/generated/`.
5. Rely on the shared contract example for mock mode so Java Live and mock expose the same keys and physical configuration.

If the user requests a default preset:

- Track the selected key explicitly in the draft store and bind the `<select>` value rather than using a static HTML `selected` attribute.
- Apply the default after presets load successfully.
- Do not overwrite a draft changed while the asynchronous request was in flight.
- Clear or update the selected key when loading a non-preset configuration.

## Test and verify

Add focused coverage for:

- Total preset count, key ordering, lookup, validation, and finite integration.
- Imported body counts and important global settings.
- Default selection and protection of edits made during loading.
- Browser selection of every new preset and the resulting body count.

Run the smallest relevant checks first, then broader checks when tools are available:

```powershell
mvn -pl simulation-core -am test
Set-Location frontend
npm.cmd run generate:contracts
npx.cmd vitest run src/stores/__tests__/draft.test.ts --environment node
npm.cmd run build
npx.cmd playwright test e2e/preset-selection.spec.ts
Set-Location ..
git -c core.safecrlf=false diff --check
git status --short
```

Use the repository's normal `npm test` when its configured DOM environment works. Report missing Maven/JDK or environment failures explicitly; do not claim Java verification from frontend checks.

Finally, review only the task files, confirm no build artifacts were added, and report the key mapping, default behavior, exact checks run, and any unverified Java risk.
