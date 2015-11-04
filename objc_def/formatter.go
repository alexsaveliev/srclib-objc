package objc_def

import (
	"sourcegraph.com/sourcegraph/srclib/graph"
)

func init() {
	graph.RegisterMakeDefFormatter("ObjectiveC", newDefFormatter)
}

func newDefFormatter(s *graph.Def) graph.DefFormatter {
	return defFormatter{s}
}

type defFormatter struct {
	def  *graph.Def
}

func (f defFormatter) Language() string { return "Objective-C" }

func (f defFormatter) DefKeyword() string {
	switch f.def.Kind {
		case "CLASS":
			return "class"
		case "ENUM":
			return "enum"
		case "METHOD":
			return "method"
		default:
			return ""
	}
}

func (f defFormatter) Kind() string { return f.def.Kind }

func (f defFormatter) Name(qual graph.Qualification) string {
	return f.def.Name
}

func (f defFormatter) Type(qual graph.Qualification) string {
  switch f.def.Kind {
		case "CLASS":
			return "class"
		case "ENUM":
			return "enum"
		default:
			return ""
	}
}

func (f defFormatter) NameAndTypeSeparator() string {
  return " "
}
