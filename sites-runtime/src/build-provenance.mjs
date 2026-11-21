const SCHEMA_SQL = "SELECT name FROM sqlite_master WHERE type = 'table'";

export async function readBuildProvenance(db, provenance) {
  if (provenance == null) return null;
  let available;
  try {
    const { results } = await db.prepare(SCHEMA_SQL).bind().all();
    if (!Array.isArray(results)) throw new Error("D1 schema rows unavailable");
    available = new Set(results.map(row => row.name));
  } catch {
    return { ...provenance, schema: "unavailable", missingSchema: [] };
  }

  const missingSchema = provenance.migrations.flatMap(migration => {
    const tables = migration.requiredTables.filter(table => !available.has(table));
    return tables.length ? [{ file: migration.file, tables }] : [];
  });
  return {
    ...provenance,
    schema: missingSchema.length ? "missing_tables" : "tables_present",
    missingSchema,
  };
}
