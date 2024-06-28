;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.expressions
  (:require [cljs-bean.core :refer [->clj]]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as S]
            [clojure.walk :refer [stringify-keys]]
            [clojure.zip :as zip]
            ["ebnf" :as ebnf]))

(declare read-ast)

(def supported-contexts
  #{"env" "process" "step"})

(def stdlib
  {
   ;; Test status functions
   "always"  {:arity 0 :fn (constantly true)}
   "success" {:arity 0 :fn #(not (get-in % [:state :failed]))}
   "failure" {:arity 0 :fn #(boolean (get-in % [:state :failed]))}
   })

(def BEGIN_INTERP "${{")

(defn interp-positions [text]
  (loop [results []
         pos 0]
    (if-let [pos (S/index-of text BEGIN_INTERP pos)]
      (recur (conj results pos)
             (+ pos (count BEGIN_INTERP)))
      results)))

;; ALL_CAPS nodes are not returned in ast
;; See also: https://www.ietf.org/rfc/rfc4627.txt
(def grammar
  "
InterpolatedText       ::= ( InterpolatedExpression | PrintableChar )*
InterpolatedExpression ::= BEGIN_INTERP Expression END_INTERP
BEGIN_INTERP ::= '${{'
END_INTERP   ::= '}}'

Expression ::= BinaryExpression | UnaryExpression

BinaryExpression ::= UnaryExpression BinOp Expression
BinOp ::= '==' | '!=' | '&&' | '||' | '+' | '-' | '*' | '/'

UnaryExpression  ::= WHITESPACE* (Value | FunctionCall | MemberExpression | ParensExpression) WHITESPACE*
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

MemberExpression ::= ContextName Property*
ContextName      ::= Identifier
Property         ::= SEP_PROP_DOT PropertyName | BEGIN_PROP_BRACK Expression END_PROP_BRACK
PropertyName     ::= Identifier
SEP_PROP_DOT     ::= WHITESPACE* '.' WHITESPACE*
BEGIN_PROP_BRACK ::= WHITESPACE* '[' WHITESPACE*
END_PROP_BRACK   ::= WHITESPACE* ']' WHITESPACE*

Identifier       ::= IDENTIFIER_START IDENTIFIER_PART*
IDENTIFIER_START ::= [a-zA-Z] | '$' | '_'
IDENTIFIER_PART  ::= [a-zA-Z0-9] | '$' | '_'

PrintableChar ::= #x0009 | #x000A | #x000D | [#x0020-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
WHITESPACE    ::= [#x20#x09#x0A#x0D]+   /* Space | Tab | \\n | \\r */

/* Used for error reporting, not evaluated directly */
ExpectedInterpolation ::= InterpolatedExpression PrintableChar*
  ")

(def parser
  (ebnf/Grammars.W3C.Parser. grammar))

(defn semantic-errors [ast]
  (let [{:keys [children text type]} ast]
    (case type

      "InterpolatedText"
      (let [positions (interp-positions text)
            expressions (filter #(= "InterpolatedExpression" (:type %)) children)]
        (when-not (= (count positions)
                     (count expressions))
          ;; Provide location of unparseable/unclosed expressions
          (mapcat (fn [pos]
                    (when-not (-> (subs text pos)
                                  (read-ast "ExpectedInterpolation")
                                  :children
                                  first)
                      [{:message (str "Invalid Expression at position " pos)}]))
                  positions)))

      "FunctionCall"
      (let [[func & args] children
            func-name (:text func)
            func (get stdlib func-name)]
        (cond
          (not func)
          [{:message (str "ReferenceError: " func-name " is not supported")}]

          (not= (count args) (:arity func))
          [{:message (str "ArityError: incorrect number of arguments to " func-name)}]

          :else nil))

      "ContextName"
      (when-not (contains? supported-contexts text)
        [{:message (str "ReferenceError: " text " is not supported")}])

      ;; else
      nil)))

(defn ast-zipper [ast]
  (zip/zipper
    #(and (map? %) (:type %))
    :children
    #(assoc %1 :children %2)
    ast))

(defn check-ast [ast]
  (loop [loc (ast-zipper ast)]
    (if (zip/end? loc)
      (zip/root loc)
      (let [node (zip/node loc)
            errors (semantic-errors node)
            loc (if (seq errors)
                  (zip/replace loc (update node :errors #(into errors %)))
                  loc)]
        (recur (zip/next loc))))))

(defn flatten-errors [ast]
  (loop [loc (ast-zipper ast)
         state []]
    (if (zip/end? loc)
      state
      (let [state (into state (:errors (zip/node loc)))]
        (recur (zip/next loc)
               state)))))

(defn read-ast [text & [start]]
  (let [start (or start "Expression")
        ;; Ensure type/text are present, if ebnf can't parse
        ;; Rely on check-ast to flag issues
        ast (merge {:type start :text text}
                   (->clj (.getAST parser text start)))]
    (check-ast ast)))

(defn print-obj [obj]
  (if (string? obj)
    obj
    (js/JSON.stringify (clj->js obj))))

(defn print-objs [objs]
  (S/join "" (map print-obj objs)))

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
      (throw (ex-info "Unchecked errors" errors)))

    (case type
      "InterpolatedText"       (map eval children)
      "InterpolatedExpression" (eval (first children))

      "PrintableChar"    text
      "Expression"       (eval (first (:children ast)))
      "ParensExpression" (eval (first (:children ast)))

      "UnaryExpression"  (eval (first children))
      "BinaryExpression" (let [[a op b] children]
                           (case (:text op)
                             "==" (= (eval a) (eval b))
                             "!=" (not= (eval a) (eval b))
                             "&&" (and (eval a) (eval b))
                             "||" (or (eval a) (eval b))
                             "+"  (+ (eval a) (eval b))
                             "-"  (- (eval a) (eval b))
                             "*"  (* (eval a) (eval b))
                             "/"  (/ (eval a) (eval b))))

      "FunctionCall"     (let [[func & args] children
                               func (get-in stdlib [(:text func) :fn])
                               args (mapv eval args)]
                           (apply func context args))
      "MemberExpression" (let [[context & props] children
                               context (eval context)
                               props (mapv eval props)]
                           (get-in context props))
      "ContextName"      (let [ident-name text
                               context (stringify-keys context)]
                           (get-in context [ident-name]))
      "Property"         (eval (first children))
      "PropertyName"     text

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

(defn read-eval
  [context text]
  (eval-ast context (read-ast text "Expression")))

(defn read-eval-print
  [context text]
  (print-obj (read-eval context text)))

(defn interpolate-text [context text]
  (print-objs (eval-ast context (read-ast text "InterpolatedText"))))

(defn explain-refs [context text]
  (let [ast (read-ast text "Expression")
        refs (loop [loc (ast-zipper ast)
                    refs #{}]
               (if (zip/end? loc)
                 refs
                 (let [node (zip/node loc)]
                   (if (= "MemberExpression" (:type node))
                     ;; Add member/context name to refs (and do not recurse _into_ member expression)
                     (let [loc (zip/replace loc (assoc node :children []))
                           refs (conj refs (:text node))]
                       (recur (zip/next loc)
                              refs))

                     ;; Recurse
                     (recur (zip/next loc)
                            refs)))))]
    (reduce #(assoc %1 %2 (read-eval context %2))
            {}
            refs)))
