// Command example is a minimal anydm plugin, standard library only. It serves links of the form
// http(s)://HOST/example/<id> and fetches HOST/example/<id>.bin. See cli/docs/plugins.md for the contract.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	protocol  = 3
	downloads = "/downloads"
	version   = "0.1.0"
)

var linkRe = regexp.MustCompile(`^/example/([A-Za-z0-9_-]{1,64})$`)

var out = json.NewEncoder(os.Stdout)

func emit(v map[string]any) { _ = out.Encode(v) }

// parse reads the arguments after the command: `--protocol N` (its value is the next argument), other
// `--name=value` options and bare `--flag`s.
func parse(args []string) (map[string]string, bool) {
	opts := map[string]string{}
	for i := 0; i < len(args); i++ {
		a := args[i]
		if !strings.HasPrefix(a, "--") {
			return nil, false
		}
		k, v, hasValue := strings.Cut(a[2:], "=")
		if k == "protocol" && !hasValue {
			if i+1 >= len(args) {
				return nil, false
			}
			i++
			v = args[i]
		}
		opts[k] = v
	}
	return opts, true
}

// match returns the id of an example link, or "".
func match(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return ""
	}
	if m := linkRe.FindStringSubmatch(u.Path); m != nil {
		return m[1]
	}
	return ""
}

// source is the file behind a link.
func source(raw string) string {
	u, _ := url.Parse(raw)
	u.RawQuery, u.Fragment = "", ""
	u.Path += ".bin"
	return u.String()
}

func fail(msg string) int {
	emit(map[string]any{"type": "error", "message": msg, "partial": []string{}})
	return 1
}

func info(raw, id string) int {
	resp, err := http.Head(source(raw))
	if err != nil {
		return fail("cannot reach the site: " + err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fail("the site answered " + resp.Status)
	}
	line := map[string]any{"type": "info", "title": id}
	if resp.ContentLength >= 0 {
		line["size"] = resp.ContentLength
	}
	emit(line)
	return 0
}

func download(raw, id string) int {
	resp, err := http.Get(source(raw))
	if err != nil {
		return fail("cannot reach the site: " + err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fail("the site answered " + resp.Status)
	}
	name := id + ".bin"
	final := filepath.Join(downloads, name)
	part := final + ".part"
	// Remove a leftover part first (this drops a planted symlink itself, not its target), then create it
	// exclusively without following links.
	_ = os.Remove(part)
	f, err := os.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY|syscall.O_NOFOLLOW, 0o644)
	if err != nil {
		return fail("cannot write to the output folder: " + err.Error())
	}
	var total any
	if resp.ContentLength >= 0 {
		total = resp.ContentLength
	}
	start, last := time.Now(), time.Time{}
	var done int64
	buf := make([]byte, 32*1024)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				f.Close()
				return fail("cannot write to the output folder: " + werr.Error())
			}
			done += int64(n)
			if time.Since(last) > 100*time.Millisecond {
				last = time.Now()
				speed := float64(done) / max(time.Since(start).Seconds(), 0.001)
				emit(map[string]any{"type": "progress", "done": done, "total": total, "speed": speed, "name": name})
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			f.Close()
			return fail("the download broke off: " + rerr.Error())
		}
	}
	if err := f.Close(); err != nil {
		return fail("cannot write to the output folder: " + err.Error())
	}
	if err := os.Rename(part, final); err != nil {
		return fail("cannot write to the output folder: " + err.Error())
	}
	emit(map[string]any{"type": "progress", "done": done, "total": done, "speed": 0, "name": name})
	emit(map[string]any{"type": "done", "paths": []string{final}})
	return 0
}

func run(argv []string) int {
	emit(map[string]any{"type": "hello", "protocol": protocol})
	if len(argv) == 0 {
		fmt.Fprintln(os.Stderr, "usage: example manifest|check|download|info --protocol N [--url=URL]")
		return 2
	}
	cmd := argv[0]
	opts, ok := parse(argv[1:])
	if !ok {
		fmt.Fprintln(os.Stderr, "arguments are --protocol N and --name=value")
		return 2
	}
	if p, err := strconv.Atoi(opts["protocol"]); err != nil || p != protocol {
		fmt.Fprintf(os.Stderr, "unsupported protocol %q\n", opts["protocol"])
		return 2
	}
	if cmd == "manifest" {
		emit(map[string]any{
			"type": "manifest", "name": "example", "version": version,
			"capabilities": []string{"check", "download", "info"}, "permissions": []string{"network"},
			"description": "Example plugin: fetches HOST/example/<id>.bin for links like http://HOST/example/<id>",
		})
		return 0
	}
	raw := opts["url"]
	id := match(raw)
	switch cmd {
	case "check":
		emit(map[string]any{"type": "supported", "value": id != ""})
		return 0
	case "download", "info":
		if id == "" {
			return fail("not an example link")
		}
		if cmd == "info" {
			return info(raw, id)
		}
		return download(raw, id)
	}
	fmt.Fprintf(os.Stderr, "unknown command %q\n", cmd)
	return 2
}

func main() { os.Exit(run(os.Args[1:])) }
