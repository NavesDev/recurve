// Architecture rules for web/src, as described in web/README.md.
// Local rules rather than a plugin: three small checks, fully tested in
// architecture.test.js, with no configuration language to learn.
import path from 'node:path';

const SHARED = 'shared';
// Test helpers are imported by tests only; they belong to no feature.
const NEUTRAL = new Set([SHARED, 'test']);
const ENTRY_POINTS = new Set(['index', 'index.ts', 'index.tsx']);

/** Splits a path under `src/` into its segments, or null outside `src/`. */
function underSrc(file) {
  const normalized = file.split(path.sep).join('/');
  const marker = normalized.lastIndexOf('/src/');
  return marker === -1 ? null : normalized.slice(marker + '/src/'.length).split('/');
}

/** The top-level folder a file belongs to, or null for a file at the root of src/. */
function areaOf(segments) {
  return segments.length > 1 ? segments[0] : null;
}

function sourcesOf(node) {
  return node.source && typeof node.source.value === 'string' ? node.source : null;
}

export const featureBoundaries = {
  meta: {
    type: 'problem',
    docs: { description: 'Features meet only through shared/ and each other\'s index file.' },
    messages: {
      sharedImportsFeature: "shared/ must not import the '{{to}}' feature: shared code knows no feature.",
      privateFile: "'{{source}}' is private to the '{{to}}' feature; import from its index file instead.",
    },
    schema: [],
  },
  create(context) {
    const fromSegments = underSrc(context.filename);
    if (!fromSegments) return {};
    const from = areaOf(fromSegments);
    if (from === 'test') return {};

    function check(sourceNode) {
      if (!sourceNode || !sourceNode.value.startsWith('.')) return;
      const target = underSrc(path.resolve(path.dirname(context.filename), sourceNode.value));
      if (!target) return;
      const to = areaOf(target) ?? (target.length === 1 && !target[0].includes('.') ? target[0] : null);
      if (!to || to === from || NEUTRAL.has(to)) return;

      if (from === SHARED) {
        context.report({ node: sourceNode, messageId: 'sharedImportsFeature', data: { to } });
        return;
      }
      const inside = target.slice(1);
      if (inside.length === 0 || (inside.length === 1 && ENTRY_POINTS.has(inside[0]))) return;
      context.report({ node: sourceNode, messageId: 'privateFile', data: { source: sourceNode.value, to } });
    }

    return {
      ImportDeclaration: (node) => check(sourcesOf(node)),
      ExportNamedDeclaration: (node) => check(sourcesOf(node)),
      ExportAllDeclaration: (node) => check(sourcesOf(node)),
      ImportExpression: (node) => check(node.source && node.source.type === 'Literal' ? node.source : null),
    };
  },
};

/** The text of a string literal or an expression-free template literal, else null. */
function stringOf(node) {
  if (node.type === 'Literal' && typeof node.value === 'string') return node.value;
  if (node.type === 'TemplateLiteral' && node.expressions.length === 0) return node.quasis[0].value.cooked;
  return null;
}

function isModuleSource(node) {
  const parent = node.parent;
  return Boolean(parent && parent.source === node);
}

function fileEndsWith(context, suffix) {
  return context.filename.split(path.sep).join('/').endsWith(suffix);
}

const PERMISSION = /^(VIEW|MANAGE)_[A-Z_]+$/;

export const noLiteralPermission = {
  meta: {
    type: 'problem',
    docs: { description: 'Permission names come from shared/constants/permissions.ts.' },
    messages: { literal: "Use PERMISSIONS from shared/constants/permissions.ts instead of '{{value}}'." },
    schema: [],
  },
  create(context) {
    if (fileEndsWith(context, 'src/shared/constants/permissions.ts')) return {};
    function check(node) {
      const value = stringOf(node);
      if (value !== null && PERMISSION.test(value)) context.report({ node, messageId: 'literal', data: { value } });
    }
    return { Literal: check, TemplateLiteral: check };
  },
};

const API_PATH = /^\/api(\/|$)/;
const ROUTE_PATH = /^\/[a-z][a-z0-9-]*(\/\S*)?$/;

export const noLiteralPath = {
  meta: {
    type: 'problem',
    docs: { description: 'Route paths live in shared/constants/routes.ts; API paths in api.ts files.' },
    messages: {
      route: "Use ROUTES from shared/constants/routes.ts instead of '{{value}}'.",
      api: "API paths belong in a feature's api.ts or shared/api, not '{{value}}'.",
    },
    schema: [],
  },
  create(context) {
    const normalized = context.filename.split(path.sep).join('/');
    const apiAllowed = normalized.endsWith('/api.ts') || normalized.includes('/src/shared/api/')
      || normalized.endsWith('/src/shared/constants/api.ts');
    const routesAllowed = normalized.endsWith('/src/shared/constants/routes.ts');

    function check(node) {
      if (isModuleSource(node)) return;
      const value = stringOf(node);
      if (value === null) return;
      if (API_PATH.test(value)) {
        if (!apiAllowed) context.report({ node, messageId: 'api', data: { value } });
      } else if (ROUTE_PATH.test(value) && !routesAllowed) {
        context.report({ node, messageId: 'route', data: { value } });
      }
    }
    return { Literal: check, TemplateLiteral: check };
  },
};

export default {
  meta: { name: 'architecture' },
  rules: {
    'feature-boundaries': featureBoundaries,
    'no-literal-permission': noLiteralPermission,
    'no-literal-path': noLiteralPath,
  },
};
