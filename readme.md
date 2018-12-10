# Example BSP server

To try it out,
```sh
git clone git@github.com:olafurpg/bsp-example.git
cd bsp-example
sbt bill/test
```

The output will look something like this
```sh
tests.BillSuite#future:66 client.publishDiagnostics: [PublishDiagnosticsParams [
  textDocument = TextDocumentIdentifier [
    uri = "file:///var/folders/cf/bm0sz8xx5d361s2yj97tv3200000gp/T/bill648534356689392474/src/com/App.scala"
  ]
  buildTarget = BuildTargetIdentifier [
    uri = "id"
  ]
  diagnostics = SeqWrapper (
    Diagnostic [
      range = Range [
        start = Position [
          line = 2
          character = -3
        ]
        end = Position [
          line = 2
          character = -1
        ]
      ]
      severity = null
      code = null
      source = null
      message = "type mismatch;\n found   : String("")\n required: Int"
      relatedInformation = null
    ]
  )
  reset = true
  originId = null
]]
```

It's not possible to integrate this build tool until Metals implements BSP server discovery.
