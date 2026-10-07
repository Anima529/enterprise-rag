# DOC 测试素材

以下二进制文件来自 Apache POI 5.5.1 官方测试数据，适用 Apache License 2.0；随附该版本 JAR 中的 LICENSE-POI.txt 和 NOTICE-POI.txt。它们只用于测试，不随生产应用发布。

- [empty.doc](https://github.com/apache/poi/blob/REL_5_5_1/test-data/document/empty.doc)：SHA-256 `5f421a3970c70f12296073478380c0849a54c03fb2f4842392c7d3c5039bf1eb`，用于生成合成中文 DOC，生成代码见 LegacyDocFixtures。
- [simple-table.doc](https://github.com/apache/poi/blob/REL_5_5_1/test-data/document/simple-table.doc)：SHA-256 `67d7fe8e0555c16516354498a5935f24eeef90beb5efbf526f93a1d6c734da43`，用于验证实际 Word 表格。
- [SimpleMacro.doc](https://github.com/apache/poi/blob/REL_5_5_1/test-data/document/SimpleMacro.doc)：SHA-256 `39e9608c711d38f298ed20a5bfae12fcdaed6f4be2be4d26db670475216ca393`，只读取 OLE 目录以验证宏文件被拒绝，绝不运行宏。
