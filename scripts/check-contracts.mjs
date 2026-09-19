import { spawnSync } from 'node:child_process';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateOpenApiContract } from './validate-openapi-contract.mjs';

const contract = 'contracts/openapi/core.yaml';
await validateOpenApiContract(readFileSync(contract, 'utf8'), contract);

const temporary = mkdtempSync(join(tmpdir(), 'routiqo-contract-'));
try {
  const generated = join(temporary, 'schema.d.ts');
  const result = spawnSync(
    process.execPath,
    ['node_modules/openapi-typescript/bin/cli.js', contract, '-o', generated],
    { stdio: 'inherit' },
  );
  if (result.status !== 0) process.exitCode = result.status ?? 1;
  else if (
    readFileSync('packages/api-client/src/schema.d.ts', 'utf8') !== readFileSync(generated, 'utf8')
  ) {
    console.error(
      'Generated API types drifted. Run pnpm contracts:generate and review the result.',
    );
    process.exitCode = 1;
  } else console.log('OpenAPI generated types are in sync.');
} finally {
  // Remove only the temporary directory created above.
  rmSync(temporary, { recursive: true });
}
