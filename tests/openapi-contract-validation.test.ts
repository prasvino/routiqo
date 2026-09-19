import { describe, expect, it } from 'vitest';
import { validateOpenApiContract } from '../scripts/validate-openapi-contract.mjs';

const contract = (response: string, secondOperation = '') => `
openapi: 3.0.3
info: { title: Contract fixture, version: 1.0.0 }
paths:
  /first:
    get:
      operationId: readFixture
      responses:
        '409': ${response}
${secondOperation}`;

describe('OpenAPI contract validation', () => {
  it('rejects response fields accidentally created by commas in a YAML flow map', async () => {
    await expect(
      validateOpenApiContract(
        contract('{ description: Current journey, consent, context, or route state changed. }'),
        'malformed-response.yaml',
      ),
    ).rejects.toThrow('#/paths/~1first/get/responses/409/consent');
  });

  it('accepts a quoted comma-bearing response description', async () => {
    await expect(
      validateOpenApiContract(
        contract('{ description: "Current journey, consent, context, or route state changed." }'),
        'valid-response.yaml',
      ),
    ).resolves.toBeUndefined();
  });

  it('accepts response-map and Response Object extensions plus reusable references', async () => {
    const source = `${contract(`
          description: Conflict.
          x-private-policy: transient
        x-response-policy: { mode: private }
`)}
components:
  responses:
    SharedConflict:
      $ref: '#/components/responses/ConflictBody'
    ConflictBody:
      description: Conflict.
      x-private-policy: transient
`;
    await expect(validateOpenApiContract(source, 'extensions.yaml')).resolves.toBeUndefined();
  });

  it('rejects stray fields in reusable Response Objects', async () => {
    const source = `${contract('{ description: Conflict. }')}
components:
  responses:
    SharedConflict: { description: Current journey, consent, or route changed. }
`;
    await expect(validateOpenApiContract(source, 'malformed-component.yaml')).rejects.toThrow(
      '#/components/responses/SharedConflict/consent',
    );
  });

  it('retains openapi-typescript duplicate operationId validation', async () => {
    await expect(
      validateOpenApiContract(
        contract(
          '{ description: Conflict. }',
          `  /second:
    post:
      operationId: readFixture
      responses:
        '200': { description: Accepted. }
`,
        ),
        'duplicate-operation.yaml',
      ),
    ).rejects.toThrow('unique `operationId`');
  });
});
