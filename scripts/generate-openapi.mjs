// Single-source OpenAPI bindings; generated outputs are intentionally ignored by git.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const root = fileURLToPath(new URL("../", import.meta.url));
const spec = JSON.parse(readFileSync(resolve(root, "api/openapi.json"), "utf8"));
const check = process.argv.includes("--check");
if (spec.openapi !== "3.1.0") throw Error("Expected OpenAPI 3.1");
const schemas = spec.components.schemas;
function refName(ref) {
  if (!ref?.startsWith("#/components/schemas/")) throw Error("Invalid schema reference: " + ref);
  const name = ref.slice("#/components/schemas/".length);
  if (!schemas[name]) throw Error("Unknown schema: " + name);
  return name;
}
function type(schema, prefix = "") {
  if (schema.$ref) return prefix + refName(schema.$ref);
  if (schema.anyOf) return schema.anyOf.map(s => type(s, prefix)).join(" | ");
  if (schema.enum) return schema.enum.map(JSON.stringify).join(" | ");
  if (schema.type === "array") return type(schema.items, prefix) + "[]";
  if (schema.type === "object") return schema.additionalProperties && schema.additionalProperties !== true
    ? "Record<string, " + type(schema.additionalProperties, prefix) + ">" : "Record<string, unknown>";
  if (["string", "number", "boolean", "null"].includes(schema.type)) return schema.type;
  throw Error("Unsupported schema: " + JSON.stringify(schema));
}
const ops = [];
for (const [path, methods] of Object.entries(spec.paths)) for (const [method, op] of Object.entries(methods)) {
  const id = op.operationId;
  if (!["get", "post"].includes(method) || !/^[a-z]\w+$/.test(id) || ops.some(x => x.id === id)) throw Error("Invalid operation " + id);
  const params = [...path.matchAll(/\{(\w+)\}/g)].map(match => match[1]);
  const declared = (op.parameters ?? []).filter(p => p.in === "path").map(p => p.name);
  if (JSON.stringify(params.slice().sort()) !== JSON.stringify(declared.slice().sort())) throw Error("Undeclared path parameter: " + id);
  const body = op.requestBody?.content?.["application/json"]?.schema;
  const res = op.responses?.["200"]?.content;
  if (!res || !op["x-kotlin-request"] || !op["x-kotlin-response"]) throw Error("Incomplete operation: " + id);
  const response = res[Object.keys(res)[0]].schema;
  type(response); if (body) type(body);
  ops.push({ id, path, method, params, body, response, runtime: op["x-runtime"], ktRequest: op["x-kotlin-request"], ktResponse: op["x-kotlin-response"] });
}
// Fail codegen if a JVM route is absent or a hosted literal endpoint drifts.
const server = readFileSync(resolve(root, "src/main/kotlin/com/clawfare/web/Server.kt"), "utf8");
const hosted = readFileSync(resolve(root, "sites-runtime/src/read-only-api.mjs"), "utf8");
for (const op of ops) {
  if (op.runtime !== "hosted") {
    const declaration = "api." + op.method + "(" + (op.method === "get" ? "GetEndpoints." : "PostEndpoints.") + op.id + ")";
    if (!server.includes(declaration)) throw Error("Missing Ktor route registration: " + declaration);
  }
  if (op.runtime !== "jvm" && op.params.length === 0 && !hosted.includes(JSON.stringify(op.path))) {
    throw Error("Missing hosted operation: " + op.path);
  }
}
const banner = "// Generated from api/openapi.json by scripts/generate-openapi.mjs. DO NOT EDIT.";
const types = [banner, "export type Nullable<T> = T | null | undefined;"];
for (const [name, schema] of Object.entries(schemas)) {
  if (schema.type === "object" && schema.properties) {
    const required = new Set(schema.required ?? []);
    types.push("export interface " + name + " {");
    for (const [field, shape] of Object.entries(schema.properties)) types.push("  " + field + (required.has(field) ? "" : "?") + ": " + type(shape) + ";");
    types.push("}");
  } else types.push("export type " + name + " = " + type(schema) + ";");
}
const client = [banner, 'import type * as Models from "./openapi-types.ts";',
  "export type ApiRoute = " + ops.map(op => op.params.length ? String.fromCharCode(96) + op.path.replace(/\{[^}]+\}/g, "$" + "{string}") + String.fromCharCode(96) : JSON.stringify(op.path)).join(" | ") + ";",
  'export type ExportRoute = "/api/export/html" | "/api/export/csv";',
  "export interface WireResponse { readonly status: number; readonly body: unknown; readonly text: string; readonly consent: string | null }",
  "export type Transport = (path: ApiRoute, body?: unknown, consent?: string | null) => Promise<WireResponse>;",
  "export interface ApiOperations {"];
for (const op of ops) client.push("  " + op.id + ": { readonly request: " + (op.body ? type(op.body, "Models.") : "void") + "; readonly response: " + type(op.response, "Models.") + " };");
client.push("}", "export function createGeneratedClient(send: Transport) {", "  return {");
for (const op of ops) {
  const args = [...op.params.map(p => p + ": string"), ...(op.body ? ["body: " + type(op.body, "Models."), "consent?: string | null"] : [])];
  if (op.id === "getHistory") args.push("limit?: number");
  const expression = op.path.split(/(\{\w+\})/g).filter(Boolean).map(part => part[0] === "{" ? "encodeURIComponent(" + part.slice(1, -1) + ")" : JSON.stringify(part)).join(" + ");
  const suffix = op.id === "getHistory" ? ' + (limit === undefined ? "" : "?limit=" + encodeURIComponent(String(limit)))' : "";
  client.push("    " + op.id + ": (" + args.join(", ") + "): Promise<WireResponse> => send((" + expression + suffix + ") as ApiRoute" + (op.body ? ", body, consent" : "") + "),");
}
client.push("  } as const;", "}");
const kotlin = [banner, "package com.clawfare.web.generated", "",
  "import io.ktor.server.routing.Route", "import io.ktor.server.routing.RoutingHandler",
  "import io.ktor.server.routing.get", "import io.ktor.server.routing.post", "",
  "/** Typed endpoints generated from the API contract and bound to Ktor handlers. */",
  "data class GetEndpoint<Response : Any>(val path: String)", "",
  "data class PostEndpoint<Request : Any, Response : Any>(val path: String)", "",
  "interface ClawfareApiRoutes {",
  "    fun <Response : Any> get(endpoint: GetEndpoint<Response>, handler: RoutingHandler)", "",
  "    fun <Request : Any, Response : Any> post(",
  "        endpoint: PostEndpoint<Request, Response>,",
  "        handler: RoutingHandler,",
  "    )",
  "}", "",
  "class KtorClawfareApiRoutes(private val route: Route) : ClawfareApiRoutes {",
  "    override fun <Response : Any> get(endpoint: GetEndpoint<Response>, handler: RoutingHandler) {",
  "        route.get(endpoint.path, handler)",
  "    }", "",
  "    override fun <Request : Any, Response : Any> post(",
  "        endpoint: PostEndpoint<Request, Response>,",
  "        handler: RoutingHandler,",
  "    ) {",
  "        route.post(endpoint.path, handler)",
  "    }",
  "}", "", "object GetEndpoints {"];
for (const op of ops.filter(op => op.method === "get" && op.runtime !== "hosted")) kotlin.push("    val " + op.id + " = GetEndpoint<" + op.ktResponse + ">(" + JSON.stringify(op.path) + ")");
kotlin.push("}", "", "object PostEndpoints {");
for (const op of ops.filter(op => op.method === "post" && op.runtime !== "hosted")) kotlin.push("    val " + op.id + " = PostEndpoint<" + op.ktRequest + ", " + op.ktResponse + ">(" + JSON.stringify(op.path) + ")");
kotlin.push("}");
const outputs = {
  "web/src/generated/openapi-types.ts": types.join("\n") + "\n",
  "web/src/generated/openapi-client.ts": client.join("\n") + "\n",
  "build/generated/openapi/kotlin/com/clawfare/web/generated/ClawfareApiRoutes.kt": kotlin.join("\n") + "\n",
};
for (const [path, contents] of Object.entries(outputs)) {
  const file = resolve(root, path);
  if (check) {
    if (!existsSync(file) || readFileSync(file, "utf8") !== contents) throw Error(path + " stale; run npm run api:generate");
  } else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, contents);
  }
}
console.log((check ? "Checked " : "Generated ") + ops.length + " operations, " + Object.keys(schemas).length + " models.");
