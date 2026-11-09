import { Hono } from 'hono'

type Bindings = {
  BUCKET: R2Bucket
  AUTH_TOKEN?: string // 設定されている場合のみ Bearer 認証を要求する
}

const app = new Hono<{ Bindings: Bindings }>()

// AUTH_TOKEN が設定されていれば全ルートで Authorization: Bearer <token> を要求
app.use('*', async (c, next) => {
  const token = c.env.AUTH_TOKEN
  if (token && c.req.header('Authorization') !== `Bearer ${token}`) {
    return c.text('unauthorized', 401)
  }
  await next()
})

app.get('/', (c) => c.text('ok'))

// クライアント契約: multipart/form-data, part name=file, filename=shot_<epochMillis>.jpg
app.post('/upload', async (c) => {
  let form: FormData
  try {
    form = await c.req.formData()
  } catch {
    return c.text('expected multipart/form-data', 400)
  }
  const file = form.get('file') as unknown
  if (!(file instanceof File)) return c.text('missing "file" part', 400)

  // パストラバーサル防止。想定外のファイル名はサーバー側で採番する。
  const name = /^shot_\d+\.jpg$/.test(file.name) ? file.name : `shot_${Date.now()}.jpg`
  const key = `shots/${name}`
  // 同名は上書きなので、クライアントのリトライで二重送信されても冪等。
  await c.env.BUCKET.put(key, await file.arrayBuffer(), {
    httpMetadata: { contentType: 'image/jpeg' },
  })
  return c.json({ ok: true, key })
})

// 確認用: 一覧 (?cursor=...) と取得
app.get('/shots', async (c) => {
  const res = await c.env.BUCKET.list({
    prefix: 'shots/',
    limit: 100,
    cursor: c.req.query('cursor'),
  })
  return c.json({
    keys: res.objects.map((o) => ({ key: o.key, size: o.size, uploaded: o.uploaded })),
    cursor: res.truncated ? res.cursor : null,
  })
})

app.get('/shots/:name', async (c) => {
  const obj = await c.env.BUCKET.get(`shots/${c.req.param('name')}`)
  if (!obj) return c.text('not found', 404)
  return new Response(obj.body, {
    headers: { 'content-type': obj.httpMetadata?.contentType ?? 'image/jpeg' },
  })
})

export default app
