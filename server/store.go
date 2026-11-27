package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Storage layout (plain files, no database needed for a handful of text documents):
//
//	<dir>/state.json          observers, document metadata, placements
//	<dir>/docs/<id>/<v>.md    every kept version of a document as a normal Markdown file

var (
	errNotFound = errors.New("not found")
	errGone     = errors.New("document is decohered")
)

// invalidError is returned for requests the client must not repeat unchanged (HTTP 400).
type invalidError struct{ msg string }

func (e invalidError) Error() string { return e.msg }

func invalid(format string, args ...any) error { return invalidError{fmt.Sprintf(format, args...)} }

type observer struct {
	Name     string `json:"name"`
	Platform string `json:"platform"`
	LastSeen int64  `json:"lastSeen"`
}

type document struct {
	Name       string             `json:"name"`
	Version    int                `json:"version"`
	UpdatedAt  int64              `json:"updatedAt"`
	UpdatedBy  string             `json:"updatedBy"`
	Decohered  bool               `json:"decohered"`
	Placements map[string]*string `json:"placements"` // observerId -> path (nil: inside the app)
}

type state struct {
	Observers map[string]*observer `json:"observers"`
	Documents map[string]*document `json:"documents"`
}

// Store keeps all state in memory and writes every change to disk before answering.
// One mutex serializes everything, which keeps the version check race-free.
type Store struct {
	mu   sync.Mutex
	dir  string
	keep int
	now  func() int64
	st   state
}

func OpenStore(dir string, keepVersions int) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dir, "docs"), 0o755); err != nil {
		return nil, err
	}
	s := &Store{
		dir:  dir,
		keep: keepVersions,
		now:  func() int64 { return time.Now().UnixMilli() },
		st:   state{Observers: map[string]*observer{}, Documents: map[string]*document{}},
	}
	data, err := os.ReadFile(s.statePath())
	switch {
	case errors.Is(err, os.ErrNotExist):
		return s, nil
	case err != nil:
		return nil, err
	}
	if err := json.Unmarshal(data, &s.st); err != nil {
		return nil, fmt.Errorf("reading %s: %w", s.statePath(), err)
	}
	if s.st.Observers == nil {
		s.st.Observers = map[string]*observer{}
	}
	if s.st.Documents == nil {
		s.st.Documents = map[string]*document{}
	}
	return s, nil
}

func (s *Store) RegisterObserver(id string, req registerObserverRequest) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.st.Observers[id]
	s.st.Observers[id] = &observer{Name: req.Name, Platform: req.Platform, LastSeen: s.now()}
	if err := s.save(); err != nil {
		s.st.Observers[id] = old
		if old == nil {
			delete(s.st.Observers, id)
		}
		return err
	}
	return nil
}

func (s *Store) List() []docSummary {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]docSummary, 0, len(s.st.Documents))
	for id, d := range s.st.Documents {
		placements := make([]placementDTO, 0, len(d.Placements))
		for obsID, path := range d.Placements {
			o := s.st.Observers[obsID]
			if o == nil {
				continue
			}
			placements = append(placements, placementDTO{obsID, o.Name, o.Platform, path})
		}
		sort.Slice(placements, func(i, j int) bool {
			return strings.ToLower(placements[i].ObserverName) < strings.ToLower(placements[j].ObserverName)
		})
		out = append(out, docSummary{
			ID: id, Name: d.Name, Version: d.Version, UpdatedAt: d.UpdatedAt,
			UpdatedBy: d.UpdatedBy, UpdatedByName: s.observerName(d.UpdatedBy), Decohered: d.Decohered,
			Placements: placements,
		})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func (s *Store) Get(id string) (docContent, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.content(id)
}

// Create is idempotent: an id that already exists returns the existing document (safe client retries).
func (s *Store) Create(req createDocRequest) (doc docContent, created bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.st.Documents[req.ID]; exists {
		doc, err = s.content(req.ID)
		return doc, false, err
	}
	if s.st.Observers[req.ObserverID] == nil {
		return docContent{}, false, invalid("unknown observer %s – register it first", req.ObserverID)
	}
	if err := s.writeVersion(req.ID, 1, req.Content); err != nil {
		return docContent{}, false, err
	}
	s.st.Documents[req.ID] = &document{
		Name: req.Name, Version: 1, UpdatedAt: s.now(), UpdatedBy: req.ObserverID,
		Placements: map[string]*string{req.ObserverID: req.Path},
	}
	if err := s.save(); err != nil {
		delete(s.st.Documents, req.ID)
		return docContent{}, false, err
	}
	doc, err = s.content(req.ID)
	return doc, true, err
}

// Push stores a new version if baseVersion is current. Otherwise stale is true and doc is the current state.
func (s *Store) Push(id string, req pushRequest) (doc docContent, stale bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.st.Documents[id]
	if d == nil {
		return docContent{}, false, errNotFound
	}
	if d.Decohered {
		return docContent{}, false, errGone
	}
	current, err := s.content(id)
	if err != nil {
		return docContent{}, false, err
	}
	if req.BaseVersion != d.Version {
		return current, true, nil
	}
	if req.Content == current.Content {
		return current, false, nil
	}
	if s.st.Observers[req.ObserverID] == nil {
		return docContent{}, false, invalid("unknown observer %s – register it first", req.ObserverID)
	}

	next := d.Version + 1
	if err := s.writeVersion(id, next, req.Content); err != nil {
		return docContent{}, false, err
	}
	old := *d
	d.Version, d.UpdatedAt, d.UpdatedBy = next, s.now(), req.ObserverID
	if err := s.save(); err != nil {
		*d = old
		return docContent{}, false, err
	}
	s.pruneVersions(id, next)
	doc, err = s.content(id)
	return doc, false, err
}

func (s *Store) SetPlacement(docID string, req placementRequest) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.st.Documents[docID]
	if d == nil {
		return errNotFound
	}
	if s.st.Observers[req.ObserverID] == nil {
		return invalid("unknown observer %s – register it first", req.ObserverID)
	}
	old, had := d.Placements[req.ObserverID]
	d.Placements[req.ObserverID] = req.Path
	if err := s.save(); err != nil {
		if had {
			d.Placements[req.ObserverID] = old
		} else {
			delete(d.Placements, req.ObserverID)
		}
		return err
	}
	return nil
}

func (s *Store) Decohere(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.st.Documents[id]
	if d == nil {
		return errNotFound
	}
	old := *d
	d.Decohered, d.UpdatedAt = true, s.now()
	if err := s.save(); err != nil {
		*d = old
		return err
	}
	return nil
}

// Rename changes the document's display name (each PC keeps its own file name).
func (s *Store) Rename(id, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.st.Documents[id]
	if d == nil {
		return errNotFound
	}
	if d.Decohered {
		return errGone
	}
	old := *d
	d.Name, d.UpdatedAt = name, s.now()
	if err := s.save(); err != nil {
		*d = old
		return err
	}
	return nil
}

// Resync undoes Decohere: the document is entangled again; observers merge their copies back in.
func (s *Store) Resync(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.st.Documents[id]
	if d == nil {
		return errNotFound
	}
	old := *d
	d.Decohered, d.UpdatedAt = false, s.now()
	if err := s.save(); err != nil {
		*d = old
		return err
	}
	return nil
}

// VersionCount returns how many versions of a document are kept on disk.
func (s *Store) VersionCount(id string) int {
	entries, _ := os.ReadDir(s.docDir(id))
	return len(entries)
}

func (s *Store) content(id string) (docContent, error) {
	d := s.st.Documents[id]
	if d == nil {
		return docContent{}, errNotFound
	}
	data, err := os.ReadFile(s.versionPath(id, d.Version))
	if err != nil {
		return docContent{}, err
	}
	return docContent{
		ID: id, Name: d.Name, Version: d.Version, Content: string(data),
		UpdatedAt: d.UpdatedAt, UpdatedBy: d.UpdatedBy, UpdatedByName: s.observerName(d.UpdatedBy),
	}, nil
}

func (s *Store) observerName(id string) string {
	if o := s.st.Observers[id]; o != nil {
		return o.Name
	}
	return id
}

func (s *Store) writeVersion(id string, version int, content string) error {
	if err := os.MkdirAll(s.docDir(id), 0o755); err != nil {
		return err
	}
	return writeAtomic(s.versionPath(id, version), []byte(content))
}

func (s *Store) pruneVersions(id string, newest int) {
	entries, err := os.ReadDir(s.docDir(id))
	if err != nil {
		return
	}
	for _, e := range entries {
		v, err := strconv.Atoi(strings.TrimSuffix(e.Name(), ".md"))
		if err == nil && v <= newest-s.keep {
			_ = os.Remove(filepath.Join(s.docDir(id), e.Name()))
		}
	}
}

func (s *Store) save() error {
	data, err := json.MarshalIndent(s.st, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(s.statePath(), data)
}

func (s *Store) statePath() string       { return filepath.Join(s.dir, "state.json") }
func (s *Store) docDir(id string) string { return filepath.Join(s.dir, "docs", id) }
func (s *Store) versionPath(id string, v int) string {
	return filepath.Join(s.docDir(id), strconv.Itoa(v)+".md")
}

// writeAtomic writes to a temp file and renames it, so a crash never leaves a half-written file.
func writeAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
