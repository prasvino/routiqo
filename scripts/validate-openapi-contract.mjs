import openapiTS from 'openapi-typescript';
// openapi-typescript exposes no public parsed-document hook. This pinned deep
// export lets the focused Response Object check inspect YAML before generation
// discards unknown fields; contract tests act as an upgrade compatibility tripwire.
import { parseSchema } from 'openapi-typescript/dist/lib/redoc.mjs';

const operationMethods = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];
const responseKeys = new Set(['description', 'headers', 'content', 'links']);
const referenceKeys = new Set(['$ref', 'summary', 'description']);

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function pointerPart(value) {
  return String(value).replaceAll('~', '~0').replaceAll('/', '~1');
}

function validateResponse(response, pointer, problems) {
  if (!isObject(response)) return;
  const allowed = Object.hasOwn(response, '$ref') ? referenceKeys : responseKeys;
  for (const key of Object.keys(response)) {
    if (!allowed.has(key) && !key.startsWith('x-')) {
      problems.push(`${pointer}/${pointerPart(key)} is not a valid Response Object field`);
    }
  }
}

function validateResponses(responses, pointer, problems, allowExtensions) {
  if (!isObject(responses)) return;
  for (const [status, response] of Object.entries(responses)) {
    if (allowExtensions && status.startsWith('x-')) continue;
    validateResponse(response, `${pointer}/${pointerPart(status)}`, problems);
  }
}

function validateOperation(operation, pointer, problems) {
  if (!isObject(operation)) return;
  validateResponses(operation.responses, `${pointer}/responses`, problems, true);
  if (!isObject(operation.callbacks)) return;
  for (const [callbackName, callback] of Object.entries(operation.callbacks)) {
    if (!isObject(callback) || Object.hasOwn(callback, '$ref')) continue;
    for (const [expression, pathItem] of Object.entries(callback)) {
      validatePathItem(
        pathItem,
        `${pointer}/callbacks/${pointerPart(callbackName)}/${pointerPart(expression)}`,
        problems,
      );
    }
  }
}

function validatePathItem(pathItem, pointer, problems) {
  if (!isObject(pathItem)) return;
  for (const method of operationMethods) {
    if (Object.hasOwn(pathItem, method)) {
      validateOperation(pathItem[method], `${pointer}/${method}`, problems);
    }
  }
}

export function validateResponseObjects(document) {
  const problems = [];
  if (isObject(document.paths)) {
    for (const [path, pathItem] of Object.entries(document.paths)) {
      validatePathItem(pathItem, `#/paths/${pointerPart(path)}`, problems);
    }
  }
  if (isObject(document.webhooks)) {
    for (const [name, pathItem] of Object.entries(document.webhooks)) {
      validatePathItem(pathItem, `#/webhooks/${pointerPart(name)}`, problems);
    }
  }
  if (isObject(document.components?.responses)) {
    validateResponses(document.components.responses, '#/components/responses', problems, false);
  }
  if (problems.length > 0) {
    throw new Error(`OpenAPI Response Object fields are invalid:\n${problems.join('\n')}`);
  }
}

export async function validateOpenApiContract(source, sourceName = 'openapi.yaml') {
  const document = await parseSchema(source, {
    absoluteRef: sourceName,
    resolver: {},
  });
  validateResponseObjects(document.parsed);
  // Keep openapi-typescript's Redocly validation in the gate, including its
  // error-level unique-operationId rule.
  await openapiTS(source, { silent: true });
}
