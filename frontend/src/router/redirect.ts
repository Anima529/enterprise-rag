const MAX_ASCII_WHITESPACE_CODE = 32

/** 检查重定向地址是否含空白字符。 */
function hasWhitespace(value: string): boolean {
  return [...value].some(character => character.charCodeAt(0) <= MAX_ASCII_WHITESPACE_CODE)
}

/** 只允许安全的站内非登录路径，非法地址回退到个人页。 */
export function safeRedirect(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\') ||
    hasWhitespace(value)
  ) {
    return '/profile'
  }
  try {
    const decoded = decodeURIComponent(value)
    if (decoded.startsWith('//') || decoded.includes('\\') || hasWhitespace(decoded)) {
      return '/profile'
    }
    const target = new URL(value, 'http://rag.local')
    if (target.origin !== 'http://rag.local' || /^\/login(?:\/|$)/.test(target.pathname)) {
      return '/profile'
    }
    return target.pathname + target.search + target.hash
  } catch {
    return '/profile'
  }
}
