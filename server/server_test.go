package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testToken = "test-token-0123456789-abcdef"

type client struct {
	t   *testing.T
	url string
}

func newServer(t *testing.T, keep int) (*client, *Store, string) {
	t.Helper()
	dir := t.TempDir()
	store, err := OpenStore(dir, keep)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(NewHandler(store, testToken))
	t.Cleanup(srv.Close)
	c := &client{t, srv.URL}
	c.do("PUT", "/api/observers/laptop", map[string]any{"name": "Laptop", "platform": "DESKTOP"}, 204, nil)
	c.do("PUT", "/api/observers/phone", map[string]any{"name": "Phone", "platform": "ANDROID"}, 204, nil)
	return c, store, dir
}

func (c *client) do(method, path string, body any, wantStatus int, out any) {
	c.t.Helper()
	var buf io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		buf = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.url+path, buf)
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != wantStatus {
		c.t.Fatalf("%s %s: status %d, want %d (%s)", method, path, resp.StatusCode, wantStatus, data)
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			c.t.Fatalf("decoding %s: %v", data, err)
		}
	}
}

func create(c *client, id, content string) docContent {
	var doc docContent
	path := `C:\Texte\Storyboard.md`
	c.do("POST", "/api/docs", createDocRequest{id, "Storyboard.md", content, "laptop", &path}, 201, &doc)
	return doc
}

func TestHealthNeedsNoTokenButAPIDoes(t *testing.T) {
	c, _, _ := newServer(t, 50)
	resp, _ := http.Get(c.url + "/health")
	if resp.StatusCode != 200 {
		t.Fatalf("health: %d", resp.StatusCode)
	}
	for _, auth := range []string{"", "Bearer wrong", testToken} {
		req, _ := http.NewRequest("GET", c.url+"/api/docs", nil)
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resp, _ := http.DefaultClient.Do(req)
		if resp.StatusCode != 401 {
			t.Errorf("auth %q: status %d, want 401", auth, resp.StatusCode)
		}
	}
}

func TestCreatePushAndStalePush(t *testing.T) {
	c, _, _ := newServer(t, 50)
	doc := create(c, "story", "Scene 1 ⚛ Ümlaut\n")
	if doc.Version != 1 || doc.UpdatedByName != "Laptop" || doc.Content != "Scene 1 ⚛ Ümlaut\n" {
		t.Fatalf("created: %+v", doc)
	}

	var pushed docContent
	c.do("PUT", "/api/docs/story", pushRequest{1, "Scene 1 from phone\n", "phone"}, 200, &pushed)
	if pushed.Version != 2 || pushed.UpdatedByName != "Phone" {
		t.Fatalf("pushed: %+v", pushed)
	}

	var stale docContent
	c.do("PUT", "/api/docs/story", pushRequest{1, "too late\n", "laptop"}, 409, &stale)
	if stale.Version != 2 || stale.Content != "Scene 1 from phone\n" {
		t.Fatalf("stale answer: %+v", stale)
	}
}

func TestListShowsPathsPerObserver(t *testing.T) {
	c, _, _ := newServer(t, 50)
	create(c, "story", "x\n")
	c.do("PUT", "/api/docs/story/placement", placementRequest{"phone", nil}, 204, nil)

	var list []json.RawMessage
	c.do("GET", "/api/docs", nil, 200, &list)
	if len(list) != 1 {
		t.Fatalf("list: %d entries", len(list))
	}
	// Exact field names and null path matter for the Kotlin client.
	got := string(list[0])
	for _, want := range []string{
		`"placements":[{"observerId":"laptop","observerName":"Laptop","platform":"DESKTOP","path":"C:\\Texte\\Storyboard.md"}`,
		`{"observerId":"phone","observerName":"Phone","platform":"ANDROID","path":null}`,
		`"decohered":false`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("list entry %s\nmissing %s", got, want)
		}
	}
}

func TestOnlyNewestVersionsAreKept(t *testing.T) {
	c, store, _ := newServer(t, 3)
	create(c, "d1", "v1\n")
	for v := 1; v <= 5; v++ {
		c.do("PUT", "/api/docs/d1", pushRequest{v, strings.Repeat("v", v+1) + "\n", "laptop"}, 200, nil)
	}
	var doc docContent
	c.do("GET", "/api/docs/d1", nil, 200, &doc)
	if doc.Version != 6 {
		t.Fatalf("version %d", doc.Version)
	}
	if n := store.VersionCount("d1"); n != 3 {
		t.Fatalf("kept %d versions, want 3", n)
	}
}

func TestCreateIsIdempotent(t *testing.T) {
	c, _, _ := newServer(t, 50)
	create(c, "d1", "first\n")
	var again docContent
	path := "x"
	c.do("POST", "/api/docs", createDocRequest{"d1", "a.md", "other", "laptop", &path}, 200, &again)
	if again.Content != "first\n" || again.Version != 1 {
		t.Fatalf("retry changed the document: %+v", again)
	}
}

func TestDecoheredDocumentRejectsPushes(t *testing.T) {
	c, _, _ := newServer(t, 50)
	create(c, "d1", "x\n")
	c.do("DELETE", "/api/docs/d1", nil, 204, nil)
	var list []docSummary
	c.do("GET", "/api/docs", nil, 200, &list)
	if !list[0].Decohered {
		t.Fatal("not marked decohered")
	}
	c.do("PUT", "/api/docs/d1", pushRequest{1, "y\n", "laptop"}, 410, nil)
}

func TestBadInputIsRejected(t *testing.T) {
	c, _, _ := newServer(t, 50)
	c.do("GET", "/api/docs/does-not-exist", nil, 404, nil)
	c.do("GET", "/api/docs/..%2F..%2Fetc", nil, 400, nil)
	c.do("POST", "/api/docs", createDocRequest{"../etc", "a.md", "", "laptop", nil}, 400, nil)
	c.do("POST", "/api/docs", createDocRequest{"d2", "a.md", "", "stranger", nil}, 400, nil)
	c.do("POST", "/api/docs", createDocRequest{"d3", "  ", "", "laptop", nil}, 400, nil)
	c.do("PUT", "/api/observers/x", map[string]any{"name": "X", "platform": "TOASTER"}, 400, nil)
	c.do("POST", "/api/docs", "not an object", 400, nil)
}

func TestStateSurvivesRestart(t *testing.T) {
	c, _, dir := newServer(t, 50)
	create(c, "story", "hello\n")
	c.do("PUT", "/api/docs/story", pushRequest{1, "hello again\n", "phone"}, 200, nil)

	reopened, err := OpenStore(dir, 50)
	if err != nil {
		t.Fatal(err)
	}
	doc, err := reopened.Get("story")
	if err != nil || doc.Version != 2 || doc.Content != "hello again\n" || doc.UpdatedByName != "Phone" {
		t.Fatalf("after restart: %+v, %v", doc, err)
	}
	// Versions are plain Markdown files on disk.
	if b, _ := os.ReadFile(filepath.Join(dir, "docs", "story", "1.md")); string(b) != "hello\n" {
		t.Fatalf("version file: %q", b)
	}
}

func TestRenameAndResync(t *testing.T) {
	c, _, _ := newServer(t, 50)
	create(c, "d1", "x\n")
	c.do("PUT", "/api/docs/d1/name", renameRequest{"Renamed.md"}, 204, nil)
	c.do("PUT", "/api/docs/d1/name", renameRequest{"  "}, 400, nil)
	var doc docContent
	c.do("GET", "/api/docs/d1", nil, 200, &doc)
	if doc.Name != "Renamed.md" || doc.UpdatedBy != "laptop" {
		t.Fatalf("after rename: %+v", doc)
	}

	c.do("DELETE", "/api/docs/d1", nil, 204, nil)
	c.do("PUT", "/api/docs/d1/name", renameRequest{"Again.md"}, 410, nil)
	c.do("POST", "/api/docs/d1/resync", nil, 204, nil)
	var list []docSummary
	c.do("GET", "/api/docs", nil, 200, &list)
	if list[0].Decohered {
		t.Fatal("still decohered after resync")
	}
	c.do("PUT", "/api/docs/d1", pushRequest{1, "y\n", "phone"}, 200, nil)
}
