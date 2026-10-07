export interface SourceTable {
  sheetName: string | null
  tableIndex: number | null
  columns: { key: string; label: string }[]
  rows: { rowNumber: number; cells: string[] }[]
  truncated: boolean
}
export interface SourcePreviewBlock {
  type: 'TEXT' | 'TABLE'
  text: string | null
  table: SourceTable | null
  truncated: boolean
}
