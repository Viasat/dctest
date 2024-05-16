;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.expressions
  (:require [cljs-bean.core :refer [->clj]]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as S]
            ["ebnf" :as ebnf]))

(def stdlib
  {
   ;; Test status functions
   "always"  (constantly true)
   "success" #(not (get-in % [:state :failed]))
   "failure" #(boolean (get-in % [:state :failed]))
   })

;; ALL_CAPS nodes are not returned in ast
;; See also: https://www.ietf.org/rfc/rfc4627.txt
(def grammar
  "
InterpolatedText ::= ( InterpolatedExpression | PrintableChar )*

InterpolatedExpression ::= BEGIN_INTERP WHITESPACE* Expression WHITESPACE* END_INTERP
BEGIN_INTERP           ::= '${{'
END_INTERP             ::= '}}'
WHITESPACE             ::= [#x20#x09#x0A#x0D]+   /* Space | Tab | \\n | \\r */

PrintableChar ::= #x0009 | #x000A | #x000D | [#x0020-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]

Expression ::= BinaryExpression | UnaryExpression

BinaryExpression ::= WHITESPACE* UnaryExpression WHITESPACE* BinOp WHITESPACE* Expression WHITESPACE*
BinOp ::= '&&' | '||' | '+' | '-' | '*' | '/'

UnaryExpression ::= Value | FunctionCall | MemberExpression | Identifier | ParensExpression
ParensExpression ::= BEGIN_PAREN_EXPR Expression END_PAREN_EXPR
BEGIN_PAREN_EXPR ::= WHITESPACE* '(' WHITESPACE*
END_PAREN_EXPR   ::= WHITESPACE* ')' WHITESPACE*

Value ::= Null | Boolean | Number | String | Array | Object

Null    ::= 'null'
Boolean ::= 'true' | 'false'
Number  ::= '-'? ('0' | [1-9] [0-9]*) ('.' [0-9]+)? (('e' | 'E') ( '-' | '+' )? ('0' | [1-9] [0-9]*))?

String             ::= DoubleQuotedString | SingleQuotedString
SingleQuotedString ::= \"'\" (([#x20-#x26] | [#x28-#xFFFF]) | \"'\" \"'\")* \"'\"
DoubleQuotedString ::= '\"' (([#x20-#x21] | [#x23-#x5B] | [#x5D-#xFFFF]) | #x5C (#x22 | #x5C | #x2F | #x62 | #x66 | #x6E | #x72 | #x74 | #x75 HEXDIG HEXDIG HEXDIG HEXDIG))* '\"'
HEXDIG             ::= [a-fA-F0-9]

Array       ::= BEGIN_ARRAY (Expression (SEP_ARRAY Expression)*)? END_ARRAY
BEGIN_ARRAY ::= WHITESPACE* '[' WHITESPACE*
END_ARRAY   ::= WHITESPACE* ']' WHITESPACE*
SEP_ARRAY   ::= WHITESPACE* ',' WHITESPACE*

Object          ::= BEGIN_OBJECT (Member (VALUE_SEPARATOR Member)*)? END_OBJECT
Member          ::= String NAME_SEPARATOR Expression
BEGIN_OBJECT    ::= WHITESPACE* '{' WHITESPACE*
END_OBJECT      ::= WHITESPACE* '}' WHITESPACE*
NAME_SEPARATOR  ::= WHITESPACE* ':' WHITESPACE*
VALUE_SEPARATOR ::= WHITESPACE* ',' WHITESPACE*

FunctionCall    ::= Identifier BEGIN_FUNC_ARGS (Expression (SEP_FUNC_ARGS Expression)*)? END_FUNC_ARGS
BEGIN_FUNC_ARGS ::= '('
SEP_FUNC_ARGS   ::= WHITESPACE* ',' WHITESPACE*
END_FUNC_ARGS   ::= ')'

MemberExpression ::= Identifier Property+
Property         ::= SEP_PROP_DOT PropertyName | BEGIN_PROP_BRACK Expression END_PROP_BRACK
PropertyName     ::= Identifier
SEP_PROP_DOT     ::= WHITESPACE* '.' WHITESPACE*
BEGIN_PROP_BRACK ::= WHITESPACE* '[' WHITESPACE*
END_PROP_BRACK   ::= WHITESPACE* ']' WHITESPACE*

Identifier       ::= IDENTIFIER_START IDENTIFIER_PART*
IDENTIFIER_START ::= [a-zA-Z] | '$' | '_'
IDENTIFIER_PART  ::= [a-zA-Z0-9] | '$' | '_'
  ")

(def parser
  (ebnf/Grammars.W3C.Parser. grammar))

(defn get-ast [text]
  (->clj (.getAST parser text "InterpolatedText")))

;; 'parent' key returned by ebnf is circular (normal pprint causes stackoverflow)
(defn pprint-ast [ast]
  (letfn [(remove-parent [ast]
            (-> ast
                (dissoc :parent)
                (update :children #(mapv remove-parent %))))]
    (pprint (remove-parent ast))))

(defn eval-ast
  [context ast]
  (let [eval #(eval-ast context %) ; don't forget the context
        {:keys [children errors text type]} ast]

    (when (seq errors)
      (throw (ex-info "Parsing errors" errors)))

    (case type
      "InterpolatedText"       (S/join "" (map eval children))
      "InterpolatedExpression" (let [result (eval (first children))]
                                 (if (string? result)
                                   result
                                   (js/JSON.stringify (clj->js result))))

      "PrintableChar"    text
      "Expression"       (eval (first (:children ast)))
      "ParensExpression" (eval (first (:children ast)))

      "UnaryExpression"  (eval (first children))
      "BinaryExpression" (let [[a op b] children]
                           (case (:text op)
                             "&&" (and (eval a) (eval b))
                             "||" (or (eval a) (eval b))
                             "+"  (+ (eval a) (eval b))
                             "-"  (- (eval a) (eval b))
                             "*"  (* (eval a) (eval b))
                             "/"  (/ (eval a) (eval b))))

      "FunctionCall"     (let [[func & args] children
                               func (get stdlib (:text func))
                               _ (when-not func
                                   (throw (ex-info (str "ReferenceError: " (:text func) " is not defined") {})))
                               args (mapv eval args)]
                           (apply func context args))
      "MemberExpression" (let [[ident & props] children
                               ident (eval ident)
                               props (mapv eval props)]
                           (get-in ident props))
      "Property"         (eval (first children))
      "PropertyName"     text
      "Identifier"       (let [ident-name text
                               context {"env" (:env context)}]
                           (get-in context [ident-name]))

      "Value"   (eval (first children))
      "Null"    nil
      "Number"  (edn/read-string text)
      "Boolean" (edn/read-string text)

      "String"             (eval (first children))
      "DoubleQuotedString" (-> text
                               (subs 1 (- (count text) 1))
                               (S/replace "\\\"" "\""))
      "SingleQuotedString" (-> text
                               (subs 1 (- (count text) 1))
                               (S/replace "''" "'"))

      "Array"  (mapv eval children)
      "Object" (into {} (map eval children))
      "Member" (let [[k v] children]
                 [(eval k) (eval v)]))))

(defn read-eval-print
  [context text]
  (eval-ast context (get-ast text)))
