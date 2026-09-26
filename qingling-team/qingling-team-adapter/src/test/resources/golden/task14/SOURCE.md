# Task 14 Python golden 来源

- 请求字段与默认值：`xiaopaw_team/api/schemas.py::TestRequest`
- 响应字段：`xiaopaw_team/api/schemas.py::TestResponse`
- 默认消息 ID、senderId 和响应组装：`xiaopaw_team/api/test_server.py::_handle_message`

`duration_ms` 属于运行时值，契约比较前统一归零；其余字段逐项比较。
