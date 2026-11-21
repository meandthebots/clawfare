import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { ClawfareClient } from "./api.ts";
import type { HistoryObservation, Itinerary, ResultSet } from "./domain.ts";

interface Props {
  client: ClawfareClient;
  result: ResultSet;
  fare: Itinerary;
  hosted: boolean;
  canTrack: boolean;
  onSaved: (id: string) => void;
}

type HistoryState =
  | { status: "idle" | "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; observations: HistoryObservation[] };

export function FareTracking({ client, result, fare, hosted, canTrack, onSaved }: Props) {
  const [editing, setEditing] = useState(false);
  const [trip, setTrip] = useState("");
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [history, setHistory] = useState<HistoryState>({ status: "idle" });
  const tripField = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (editing) tripField.current?.focus();
  }, [editing]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canTrack || saving) return;
    setSaving(true);
    setFeedback("");
    const request = { itineraryId: fare.id, tripSlug: trip };
    try {
      if (hosted) {
        const response = await client.trackHosted(result.id, request);
        if (!response.ok) {
          setFeedback(response.error);
          return;
        }
        onSaved(response.data.slug);
      } else {
        const response = await client.track(result.id, request);
        const body = response.body;
        if (
          !body ||
          typeof body !== "object" ||
          !("slug" in body) ||
          typeof body.slug !== "string"
        ) {
          setFeedback(
            body && typeof body === "object" && "error" in body && typeof body.error === "string"
              ? body.error
              : "Could not save the fare.",
          );
          return;
        }
        onSaved(body.slug);
      }
      setFeedback("Fare saved. Open it from Investigations.");
      setEditing(false);
    } catch {
      setFeedback("Could not save the fare.");
    } finally {
      setSaving(false);
    }
  }

  async function loadHistory() {
    setHistory({ status: "loading" });
    try {
      const response = await client.history(result.id, fare.id);
      setHistory(
        response.ok
          ? { status: "ready", observations: response.data }
          : { status: "error", message: response.error },
      );
    } catch {
      setHistory({ status: "error", message: "Could not load price history." });
    }
  }

  return (
    <section className="sec" aria-label="Fare tracking">
      <div className="actions">
        {canTrack && !editing && (
          <button type="button" id="btnTrack" onClick={() => setEditing(true)}>
            Save to a trip
          </button>
        )}
        {hosted && (
          <button
            type="button"
            disabled={history.status === "loading"}
            onClick={() => void loadHistory()}
          >
            {history.status === "loading" ? "Loading price history…" : "Price history"}
          </button>
        )}
      </div>
      {editing && (
        <form onSubmit={(event) => void save(event)}>
          <label>
            Trip name{" "}
            <input
              ref={tripField}
              value={trip}
              onChange={(event) => setTrip(event.target.value)}
              pattern={"[a-z0-9][a-z0-9_\\-]{0,99}"}
              required
              maxLength={100}
              aria-describedby="track-name-help"
            />
          </label>
          <p id="track-name-help">Use lowercase letters, numbers, hyphens or underscores.</p>
          <div className="actions">
            <button type="submit" disabled={saving}>
              {saving ? "Saving…" : "Save fare"}
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={() => {
                setEditing(false);
                setFeedback("");
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      )}
      {feedback && <p role="status">{feedback}</p>}
      {history.status === "error" && <p role="alert">{history.message}</p>}
      {history.status === "ready" && (
        <>
          <h3>Saved price observations</h3>
          {history.observations.length > 0 ? (
            <>
              <p>
                Original provider price per person
                {result.query?.market ? ` · market ${result.query.market}` : ""}. Exact seller and
                fare terms may be unknown.
              </p>
              <ul>
                {history.observations.map((row) => (
                  <li key={row.observation_id}>
                    {row.original_currency} {row.amount_decimal} · {row.provider} ·{" "}
                    {row.source_label} ·{" "}
                    <time dateTime={row.captured_at}>
                      {new Date(row.captured_at).toLocaleString()}
                    </time>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p role="status">No provider price observations were saved for this itinerary.</p>
          )}
        </>
      )}
    </section>
  );
}
