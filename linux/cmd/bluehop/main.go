package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"runtime/debug"
)

// version can be overridden at link time via:
// go build -ldflags "-X main.version=v0.1.0" -o build/bluehop ./cmd/bluehop
var version = "dev"

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("bluehop", flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage: bluehop <command> [options]")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Commands:")
		fmt.Fprintln(stderr, "  version   Print the bluehop CLI version")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Options:")
		fs.PrintDefaults()
	}

	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return 0
		}
		return 2
	}

	if fs.NArg() == 0 {
		fs.Usage()
		return 0
	}

	switch fs.Arg(0) {
	case "version":
		if fs.NArg() > 1 {
			fmt.Fprintf(stderr, "version command does not accept arguments: %v\n\n", fs.Args()[1:])
			fs.Usage()
			return 2
		}
		if _, err := fmt.Fprintf(stdout, "bluehop %s\n", resolveVersion()); err != nil {
			fmt.Fprintf(stderr, "failed to write version output: %v\n", err)
			return 1
		}
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command: %s\n\n", fs.Arg(0))
		fs.Usage()
		return 2
	}
}

func resolveVersion() string {
	if version != "" && version != "dev" {
		return version
	}

	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "dev"
	}
	return formatBuildVersion(info)
}

func formatBuildVersion(info *debug.BuildInfo) string {
	if info == nil {
		return "dev"
	}
	if info.Main.Version != "" && info.Main.Version != "(devel)" {
		return info.Main.Version
	}

	var revision string
	var dirty bool
	for _, setting := range info.Settings {
		switch setting.Key {
		case "vcs.revision":
			revision = setting.Value
		case "vcs.modified":
			dirty = setting.Value == "true"
		}
	}
	if revision == "" {
		return "dev"
	}
	if len(revision) > 7 {
		revision = revision[:7]
	}
	if dirty {
		return "dev+" + revision + "-dirty"
	}
	return "dev+" + revision
}
