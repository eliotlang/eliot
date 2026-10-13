# Eliot TextMate grammar

A [TextMate](https://macromates.com/manual/en/language_grammars) grammar that gives
syntax highlighting to Eliot source files (`.els`). It is packaged in the VS Code
extension layout, which both **IntelliJ IDEA** (via its built-in *TextMate Bundles*
support) and **VS Code** can consume directly.

```
ide/textmate/
├── package.json                  # bundle manifest (language + grammar contributions)
├── language-configuration.json   # comments, brackets, auto-closing pairs
└── syntaxes/
    └── eliot.tmLanguage.json      # the grammar itself (scope: source.eliot)
```

## Installing in IntelliJ IDEA (and other JetBrains IDEs)

IntelliJ understands TextMate bundles out of the box — no plugin needed.

1. Open **Settings/Preferences** → **Editor** → **TextMate Bundles**.
2. Click **+** and select this folder: `ide/textmate`.
3. Click **OK**. Any file ending in `.els` is now highlighted.

If `.els` files are not picked up automatically, map the extension:
**Settings** → **Editor** → **File Types** → *TextMate* (or *Eliot*) → add the
`*.els` pattern.

Colors follow the active editor theme; the grammar assigns standard TextMate scopes
(see below), so themes that map those scopes will color Eliot accordingly.

## Installing in VS Code

Copy or symlink this folder into your extensions directory and reload:

```bash
ln -s "$PWD/ide/textmate" ~/.vscode/extensions/eliot-textmate
```

(or run `code --install-extension` after packaging with `vsce package`).

## What gets highlighted

| Construct | Example | Scope |
|-----------|---------|-------|
| Line / block / doc comments | `// ...`, `/* ... */`, `/** ... */` | `comment.*.eliot` |
| Strings + escapes | `"Hello\n"` | `string.quoted.double.eliot` |
| Integer literals (incl. glued negative) | `42`, `-128` | `constant.numeric.integer.eliot` |
| Declaration keywords | `import data def type ability effect implement with uses` | `keyword.other.eliot` |
| Control keywords | `match case` | `keyword.control.eliot` |
| Fixity / precedence | `infix left at +`, `prefix`, `postfix` | `storage.modifier.*.eliot` |
| Modifiers | `opaque`, `auto` | `storage.modifier.eliot` |
| Defined function name | `def show`, `def +`, `def <===>` | `entity.name.function.eliot` |
| Defined type / data name | `type Int`, `data Box` | `entity.name.type.eliot` |
| Types & constructors (Uppercase) | `String`, `Just`, `Int` | `entity.name.type.eliot` |
| Function/constructor calls | `printLine(...)`, `hello[1]` | `entity.name.function.call.eliot` |
| Import path | `eliot.compiler.Combine` | `entity.name.namespace.eliot` |
| Wildcard | `_` | `variable.language.wildcard.eliot` |
| Operators | `-> :: : ~ + && \| =` | `keyword.operator.*.eliot` |

### Highlighting model

Eliot has no `_` in identifiers and uses **case to separate namespaces**: an
identifier starting with an upper-case letter is a *type or data constructor*, one
starting with a lower-case letter (or made of operator characters) is a *value /
function*. The grammar leans on this — upper-case names get the type scope, lower-case
names followed by `(`/`[` get the call scope. Soft keywords (`opaque`, `auto`, `left`,
`right`, `at`, `above`, `below`) are highlighted as modifiers; in the rare case one is
used as an ordinary identifier it will still be colored as a keyword (a limitation of
context-free TextMate grammars).
