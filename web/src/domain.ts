/**
 * Browser-facing projections of the Kotlin @Serializable contracts.
 *
 * Sources:
 *   fare-core/src/commonMain/kotlin/com/clawfare/search/TransportModels.kt
 *   src/main/kotlin/com/clawfare/search/{Models,RefundEvidence,SellerOption}.kt
 *
 * AppJson sets encodeDefaults=true and explicitNulls=false. Nullable members
 * are therefore optional AND can be null in other compatible JSON producers.
 * Never turn unknown prices, verified-refund evidence or fares into zero.
 */
export type Nullable<T> = T | null | undefined;
export type Cabin = "economy" | "premium_economy" | "business" | "first";
export type TripType = "round_trip" | "one_way";
export type CellStatus = "ok" | "catch" | "no";

export interface SearchQuery {
  origins: string[];
  destinations: string[];
  outboundFrom: string;
  outboundTo?: string;
  returnFrom?: Nullable<string>;
  returnTo?: Nullable<string>;
  nights?: Nullable<number>;
  tripType: TripType;
  adults: number;
  cabin: Cabin;
  cabinShortHaul: Cabin;
  strictPerLegCabin?: boolean;
  strictOperatingCarrier?: boolean;
  checkedBags: number;
  excludedAirlines?: string[];
  currency: string;
  market?: string;
  excludedTransitAirports?: string[];
  excludedTransitCountries?: string[];
  maxLiveCalls?: Nullable<number>;
  providers: string[];
  nonstopOnly: boolean;
  returnChoices: number;
  fetchReturns: boolean;
  cacheOnly: boolean;
  confirmed: boolean;
  investigation?: Nullable<string>;
}

export interface FilterSpec {
  maxStops?: Nullable<number>;
  maxLayoverMinutes?: Nullable<number>;
  excludeOutboundDates?: string[];
  excludeReturnDates?: string[];
  europeanPremiumOnly?: boolean;
  airlineAllow?: string[];
  airlineBlock?: string[];
  maxTravelMinutes?: Nullable<number>;
  outboundDepartAfter?: Nullable<string>;
  outboundDepartBefore?: Nullable<string>;
  returnDepartAfter?: Nullable<string>;
  nonstopOnly?: boolean;
  hideMock?: boolean;
  verifiedCashRefundOnly?: boolean;
  cabin?: Nullable<string>;
  nights?: Nullable<number>;
  origin?: Nullable<string>;
}

export interface Leg {
  flightNumber: string;
  airline: string;
  airlineCode: string;
  operatingCarrier: string;
  from: string;
  to: string;
  departTime: string;
  arriveTime: string;
  departDate: string;
  arriveDate: string;
  durationMinutes?: Nullable<number>;
  cabin?: Nullable<string>;
  aircraft?: Nullable<string>;
  operatingCarrierVerified?: boolean;
}
export interface Layover { airport: string; minutes: number }
export interface Direction {
  legs: Leg[];
  layovers: Layover[];
  durationMinutes?: Nullable<number>;
  stops: number;
}
export interface BookLink { kind: string; label: string; url: string }

export interface SellerRefundProof {
  sellerId: string;
  offerId: string;
  itinerarySha256: string;
  partySize: number;
  fareBrand: string;
  termsSourceUrl: string;
  termsSha256: string;
  reviewedAt: string;
  expiresAt: string;
  cancellationDeadlineAt: string;
  coversFullTicketBeforeOutbound: boolean;
  refundToTravellerOriginalPayment: boolean;
  issuerPenaltyAmount?: Nullable<number>;
  sellerServicingFeeAmount?: Nullable<number>;
  reviewerEvidence: string;
}
export interface RefundEvidence {
  provider: string;
  offerId?: Nullable<string>;
  observedAt?: Nullable<string>;
  expiresAt?: Nullable<string>;
  allowed?: Nullable<boolean>;
  penaltyAmount?: Nullable<number>;
  penaltyCurrency?: Nullable<string>;
  method: "original_payment" | "travel_credit" | "unknown" | string;
  servicingFeeAmount?: Nullable<number>;
  servicingFeeCurrency?: Nullable<string>;
  availablePassengers?: Nullable<number>;
  baggageEvidence: string[];
  sourceDescription: string;
  sellerId?: Nullable<string>;
  sellerProof?: Nullable<SellerRefundProof>;
}
export interface Itinerary {
  id: string;
  pricePerPerson?: Nullable<number>;
  priceOriginal?: Nullable<number>;
  priceCurrency: string;
  passengers: number;
  cabin: string;
  cabinKey: string;
  euPremium: boolean;
  origin: string;
  destination: string;
  returnFrom?: Nullable<string>;
  outboundDate: string;
  returnDate?: Nullable<string>;
  nights?: Nullable<number>;
  airlineSummary: string;
  outbound: Direction;
  returning?: Nullable<Direction>;
  returnMissing: boolean;
  stopsOut: number;
  stopsRet?: Nullable<number>;
  layoverMinutes: number[];
  maxLayoverMinutes?: Nullable<number>;
  travelMinutes?: Nullable<number>;
  outboundDepart?: Nullable<string>;
  returnDepart?: Nullable<string>;
  airlineCodes: string[];
  source: string;
  fareBrand?: Nullable<string>;
  farePolicyKey?: Nullable<string>;
  refundEvidence?: Nullable<RefundEvidence>;
  cashRefundProofVerified: boolean;
  sourceLabel: string;
  livePrice: boolean;
  fetchedAt?: Nullable<string>;
  links: BookLink[];
  prevPrice?: Nullable<number>;
  notes: string;
  assumedReturn: boolean;
}
export interface ComboCell {
  primaryId: string;
  addonId?: Nullable<string>;
  status: CellStatus;
  total: number;
  prevTotal?: Nullable<number>;
  reasonsNo: string[];
  catches: string[];
  oks: string[];
}
export interface VendorRow {
  vendor: string;
  market: string;
  marketLabel: string;
  currency: string;
  fareBrand: string;
  trust: "airline" | "trusted" | "caution" | "risky" | string;
  trustLabel: string;
  note: string;
  roundTrip?: Nullable<number>;
  oneWayOut?: Nullable<number>;
  oneWayBack?: Nullable<number>;
  roundTripWithBag?: Nullable<number>;
  oneWaysWithBag?: Nullable<number>;
  allInGbp?: Nullable<number>;
  how: string;
  bags: string;
  mix: boolean;
  fresh: boolean;
  priceChangeGbp?: Nullable<number>;
  isNew: boolean;
  fetchedAt?: Nullable<string>;
  links: BookLink[];
}
export interface SellerOption {
  id: string;
  productKey: string;
  itineraryId: string;
  provider: string;
  market: string;
  seller: string;
  fareBrand?: Nullable<string>;
  fareType?: Nullable<string>;
  priceTotal?: Nullable<number>;
  currency: string;
  passengers: number;
  journey: string;
  directAirline?: Nullable<boolean>;
  splitTickets: boolean;
  bags: string;
  phoneServiceFee?: Nullable<number>;
  observedAt: string;
  bookingActionAvailable: boolean;
  refundStatus: string;
}
export interface NoteCard { title: string; body: string; warn: boolean }
export interface FxInfo { perGbp: Record<string, number>; asOf: string }
export interface ResultSet {
  id: string;
  title: string;
  subtitle: string;
  fetchedAt?: Nullable<string>;
  currency: string;
  checkedBags: number;
  passengers: number;
  itineraries: Itinerary[];
  addons: Itinerary[];
  cells: ComboCell[];
  vendors: VendorRow[];
  sellerOptions: SellerOption[];
  vendorIntro: NoteCard[];
  headerNotes: NoteCard[];
  suggestedFilters: FilterSpec;
  warnings: string[];
  fx: FxInfo;
  scenarioLabel?: Nullable<string>;
  query?: Nullable<SearchQuery>;
  vendorItineraryId?: Nullable<string>;
}

export interface QuotaView {
  provider: string;
  configured: boolean;
  remaining?: Nullable<number>;
  limit?: Nullable<number>;
  used?: Nullable<number>;
  note?: Nullable<string>;
}
export interface Estimate {
  liveCalls: number;
  cachedCalls: number;
  skippedCalls: number;
  returnLookupsUpperBound: number;
  lines: string[];
  warnings: string[];
  quota: QuotaView[];
  /** Hosted estimate-only responses additionally state canRun:false. */
  canRun?: boolean;
}
export interface SearchResponse {
  ok: boolean;
  needsConfirmation?: boolean;
  estimate?: Nullable<Estimate>;
  result?: Nullable<ResultSet>;
  error?: Nullable<string>;
}
export interface StatusResponse {
  cacheDir: string;
  searchesDir: string;
  providers: QuotaView[];
}
export interface SavedInvestigation {
  id: string;
  title: string;
  subtitle?: string;
  fetchedAt?: Nullable<string>;
}
export interface StorageDiagnostic {
  backend: "d1";
  sampleStored: boolean;
  firstImportedAt?: Nullable<string>;
  sha256?: Nullable<string>;
}
export interface VendorRequest {
  itineraryId: string;
  markets: string[];
  cacheOnly: boolean;
  confirmed: boolean;
}
export interface TrackRequest { itineraryId: string; tripSlug: string }
export interface ExportRequest {
  result: ResultSet;
  filters: FilterSpec;
  kind: "itineraries" | "vendors" | "seller_options";
}

/** Deliberately structural at the network boundary, not a false full-schema proof. */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
export function isResultSet(value: unknown): value is ResultSet {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.title === "string" &&
    Array.isArray(value.itineraries) &&
    value.itineraries.every((it: unknown) => isRecord(it) && typeof it.id === "string") &&
    Array.isArray(value.addons) &&
    Array.isArray(value.cells) &&
    Array.isArray(value.vendors)
  );
}
function isInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value);
}
export function isEstimate(value: unknown): value is Estimate {
  return (
    isRecord(value) &&
    isInteger(value.liveCalls) &&
    isInteger(value.cachedCalls) &&
    isInteger(value.skippedCalls) &&
    isInteger(value.returnLookupsUpperBound) &&
    Array.isArray(value.warnings) &&
    Array.isArray(value.lines)
  );
}
export function isQuotaList(value: unknown): value is QuotaView[] {
  return Array.isArray(value) && value.every(
    (entry: unknown) => isRecord(entry) && typeof entry.provider === "string" &&
      typeof entry.configured === "boolean",
  );
}
export function isSavedList(value: unknown): value is SavedInvestigation[] {
  return Array.isArray(value) && value.every(
    (entry: unknown) => isRecord(entry) && typeof entry.id === "string" &&
      typeof entry.title === "string",
  );
}
