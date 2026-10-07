import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, linkify: false, breaks: true })

/** 将图片替换为转义后的说明文字，避免正文触发外部资源加载。 */
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index]?.content ?? '')

/** 为外链添加隔离属性并在新标签页打开。 */
markdown.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  tokens[index]?.attrSet('rel', 'noopener noreferrer')
  tokens[index]?.attrSet('target', '_blank')
  return self.renderToken(tokens, index, options)
}

/** 先转义正文，再将允许的引用标记替换为可点击按钮。 */
markdown.renderer.rules.text = (tokens, index, _options, env: { citations: Set<string> }) => {
  // 仅替换引用白名单中的编号，其余标记保持转义后的原样。
  return markdown.utils.escapeHtml(tokens[index]?.content ?? '').replace(/\[C(\d+)\]/g, (marker, number: string) => {
    const id = `C${number}`
    return env.citations.has(id)
      ? `<button class="citation-marker" data-citation="${id}" type="button">[${id}]</button>`
      : marker
  })
}

/** 渲染关闭原始 HTML 的 Markdown，仅允许白名单中的引用编号。 */
export function renderMarkdown(content: string, citationIds: string[] = []) {
  return markdown.render(content, { citations: new Set(citationIds) })
}
