export interface UsageTotals {
  calls: number
  input_tokens: number
  output_tokens: number
  estimated_cost: number | null
  unpriced_calls: number
  errors: number
  p95_ms: number
}

export interface UsageGroup {
  label: string
  calls: number
  estimated_cost: number | null
  unpriced_calls: number
  errors: number
  p95_ms: number
}

export interface RouteUsage {
  label: string
  requests: number
  errors: number
  cancelled: number
  running: number
  cache_hits: number
  p95_ms: number | null
  first_token_p95_ms: number | null
}

export interface UsageDashboard {
  totals: UsageTotals
  stages: UsageGroup[]
  models: UsageGroup[]
  daily: UsageGroup[]
  routes: RouteUsage[]
  currency: string
  scope: 'ALL' | 'SELF'
  from: string
  to: string
}

export interface PriceRequest {
  model: string
  version: string
  basis: 'TOKENS' | 'CALL' | 'UNITS'
  inputPrice: number
  outputPrice: number
}

export interface UsagePrice {
  model: string
  version: string
  basis: string
  input_price: number
  output_price: number
  currency: string
  effective_at: string
}
