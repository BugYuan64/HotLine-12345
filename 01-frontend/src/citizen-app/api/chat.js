/**
 * 智能助手流式问答（SSE over fetch）：三端共用
 * - 用 fetch + ReadableStream 而非 EventSource（EventSource 不支持 POST / 自定义请求头）
 * - 逐帧解析 SSE data 行，回调 onEvent({type, content/sources/message})
 * - 返回 abort 函数（组件卸载 / 用户停止时中断流）
 * - 支持 scope（citizen/staff/street）：决定访问的后端接口与知识库，一个端一个知识库
 *
 * @param {Object}   payload        {question, history:[{role,content}], scope}
 * @param {Function} onEvent        SSE 事件回调
 * @param {Function} [onDone]       流结束回调（正常 done / 异常均触发）
 * @param {Function} [getToken]     token 读取函数（不同端各自读取自己的 token）
 * @param {string}   [chatPath]     覆盖聊天接口路径（默认按 scope 推断）
 */
export function streamChat(payload, onEvent, onDone, { getToken, chatPath } = {}) {
  const controller = new AbortController()
  const scope = payload.scope || 'citizen'

  // 按端推断请求路径（Java 端按 prefix 做了角色隔离）
  const path = chatPath || {
    citizen: '/api/v1/public/chat/stream',
    staff: '/api/v1/staff/chat/stream',
    street: '/api/v1/street/chat/stream',
    admin: '/api/v1/admin/chat/stream'
  }[scope] || '/api/v1/public/chat/stream'

  ;(async () => {
    try {
      const resp = await fetch(path, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken ? getToken() : ''}`
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      })

      if (!resp.ok || !resp.body) {
        onEvent({ type: 'error', message: resp.status === 401 ? '登录已失效' : '智能助手服务暂时不可用' })
        onDone?.()
        return
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE 以空行分帧，逐帧解析 data: 行
        const frames = buffer.split('\n\n')
        buffer = frames.pop() || ''
        for (const frame of frames) {
          const dataLine = frame.split('\n').find(l => l.startsWith('data:'))
          if (!dataLine) continue
          try {
            onEvent(JSON.parse(dataLine.slice(5).trim()))
          } catch { /* 忽略残缺帧 */ }
        }
      }
    } catch (err) {
      if (err?.name !== 'AbortError') {
        onEvent({ type: 'error', message: '网络异常，请稍后再试' })
      }
    } finally {
      onDone?.()
    }
  })()

  return () => controller.abort()
}

/**
 * 聊天记录本地持久化（按端隔离，切换页面不丢失）
 */
export function loadChatHistory(scope) {
  try {
    const raw = localStorage.getItem(`chat_history_${scope}`)
    return raw ? JSON.parse(raw) : []
  } catch { return [] }
}

export function saveChatHistory(scope, messages) {
  try {
    // 仅持久化完整的 user/assistant 消息（跳过 streaming 中的占位与折叠状态）
    const plain = messages
      .filter(m => !m.streaming)
      .map(m => ({ role: m.role, content: m.content, sources: m.sources || null }))
    localStorage.setItem(`chat_history_${scope}`, JSON.stringify(plain.slice(-50)))
  } catch { /* 忽略存储异常 */ }
}

export function clearChatHistory(scope) {
  try { localStorage.removeItem(`chat_history_${scope}`) } catch { /* 忽略 */ }
}