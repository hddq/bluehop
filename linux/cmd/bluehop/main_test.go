package main

import (
	"bytes"
	"runtime/debug"
	"strings"
	"testing"
)

func TestRunHelpAndVersion(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := run([]string{"--help"}, &stdout, &stderr); code != 0 {
		t.Fatalf("expected exit 0 for --help, got %d (stderr=%q)", code, stderr.String())
	}
	if !strings.Contains(stderr.String(), "Usage: bluehop") {
		t.Fatalf("expected usage in stderr, got %q", stderr.String())
	}

	stdout.Reset()
	stderr.Reset()
	if code := run([]string{"version"}, &stdout, &stderr); code != 0 {
		t.Fatalf("expected exit 0 for version, got %d (stderr=%q)", code, stderr.String())
	}
	if !strings.HasPrefix(stdout.String(), "bluehop ") {
		t.Fatalf("expected version output, got %q", stdout.String())
	}
}

func TestFormatBuildVersion(t *testing.T) {
	tests := []struct {
		name string
		info *debug.BuildInfo
		want string
	}{
		{
			name: "tagged module build",
			info: &debug.BuildInfo{Main: debug.Module{Version: "v0.1.0"}},
			want: "v0.1.0",
		},
		{
			name: "clean dev vcs build",
			info: &debug.BuildInfo{
				Main: debug.Module{Version: "(devel)"},
				Settings: []debug.BuildSetting{
					{Key: "vcs.revision", Value: "47e84f71234567890"},
					{Key: "vcs.modified", Value: "false"},
				},
			},
			want: "dev+47e84f7",
		},
		{
			name: "dirty dev vcs build",
			info: &debug.BuildInfo{
				Main: debug.Module{Version: "(devel)"},
				Settings: []debug.BuildSetting{
					{Key: "vcs.revision", Value: "47e84f71234567890"},
					{Key: "vcs.modified", Value: "true"},
				},
			},
			want: "dev+47e84f7-dirty",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := formatBuildVersion(tc.info); got != tc.want {
				t.Fatalf("formatBuildVersion() = %q, want %q", got, tc.want)
			}
		})
	}
}
