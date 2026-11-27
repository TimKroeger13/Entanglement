package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// JSON shapes – must match app.entanglement.shared.model in the Kotlin `shared` module.

type placementDTO struct {
	ObserverID   string  `json:"observerId"`
	ObserverName string  `json:"observerName"`
	Platform     string  `json:"platform"`
	Path         *string `json:"path"`
}

type docSummary struct {
	ID            string         `json:"id"`
	Name          string         `json:"name"`
	Version       int            `json:"version"`
	UpdatedAt     int64          `json:"updatedAt"`
	UpdatedBy     string         `json:"updatedBy"`
	UpdatedByName string         `json:"updatedByName"`
	Decohered     bool           `json:"decohered"`
	Placements    []placementDTO `json:"placements"`
}

type docContent struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Version       int    `json:"version"`
	Content       string `json:"content"`
	UpdatedAt     int64  `json:"updatedAt"`
	UpdatedBy     string `json:"updatedBy"`
	UpdatedByName string `json:"updatedByName"`
}

type registerObserverRequest struct {
	Name     string `json:"name"`
	Platform string `json:"platform"`
}

type createDocRequest struct {
	ID         string  `json:"id"`
	Name       string  `json:"name"`
	Content    string  `json:"content"`
	ObserverID string  `json:"observerId"`
	Path       *string `json:"path"`
}

type pushRequest struct {
	BaseVersion int    `json:"baseVersion"`
	Content     string `json:"content"`
	ObserverID  string `json:"observerId"`
}

type renameRequest struct {
	Name string `json:"name"`
}

type placementRequest struct {
	ObserverID string  `json:"observerId"`
	Path       *string `json:"path"`
}

const (
	maxContentChars = 5_000_000
	maxNameChars    = 255
	maxPathChars    = 4096
	maxBodyBytes    = 16 << 20
)

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// NewHandler wires all routes. Everything below /api requires the bearer token.
func NewHandler(store *Store, token string) http.Handler {
	api := http.NewServeMux()

	api.HandleFunc("PUT /api/observers/{id}", func(w http.ResponseWriter, r *http.Request) {
		var req registerObserverRequest
		if !decode(w, r, &req) {
			return
		}
		if !validName(req.Name) {
			writeError(w, invalid("invalid observer name"))
			return
		}
		if req.Platform != "DESKTOP" && req.Platform != "ANDROID" {
			writeError(w, invalid("platform must be DESKTOP or ANDROID"))
			return
		}
		respond(w, http.StatusNoContent, nil, store.RegisterObserver(r.PathValue("id"), req))
	})

	api.HandleFunc("GET /api/docs", func(w http.ResponseWriter, r *http.Request) {
		respond(w, http.StatusOK, store.List(), nil)
	})

	api.HandleFunc("POST /api/docs", func(w http.ResponseWriter, r *http.Request) {
		var req createDocRequest
		if !decode(w, r, &req) {
			return
		}
		switch {
		case !idPattern.MatchString(req.ID):
			writeError(w, invalid("invalid document id"))
		case !validName(req.Name):
			writeError(w, invalid("invalid document name"))
		case utf8.RuneCountInString(req.Content) > maxContentChars:
			writeError(w, invalid("document too large"))
		case req.Path != nil && len(*req.Path) > maxPathChars:
			writeError(w, invalid("path too long"))
		default:
			doc, created, err := store.Create(req)
			status := http.StatusOK
			if created {
				status = http.StatusCreated
			}
			respond(w, status, doc, err)
		}
	})

	api.HandleFunc("GET /api/docs/{id}", func(w http.ResponseWriter, r *http.Request) {
		doc, err := store.Get(r.PathValue("id"))
		respond(w, http.StatusOK, doc, err)
	})

	api.HandleFunc("PUT /api/docs/{id}", func(w http.ResponseWriter, r *http.Request) {
		var req pushRequest
		if !decode(w, r, &req) {
			return
		}
		if utf8.RuneCountInString(req.Content) > maxContentChars {
			writeError(w, invalid("document too large"))
			return
		}
		doc, stale, err := store.Push(r.PathValue("id"), req)
		status := http.StatusOK
		if stale {
			status = http.StatusConflict
		}
		respond(w, status, doc, err)
	})

	api.HandleFunc("PUT /api/docs/{id}/placement", func(w http.ResponseWriter, r *http.Request) {
		var req placementRequest
		if !decode(w, r, &req) {
			return
		}
		if req.Path != nil && len(*req.Path) > maxPathChars {
			writeError(w, invalid("path too long"))
			return
		}
		respond(w, http.StatusNoContent, nil, store.SetPlacement(r.PathValue("id"), req))
	})

	api.HandleFunc("PUT /api/docs/{id}/name", func(w http.ResponseWriter, r *http.Request) {
		var req renameRequest
		if !decode(w, r, &req) {
			return
		}
		if !validName(req.Name) {
			writeError(w, invalid("invalid document name"))
			return
		}
		respond(w, http.StatusNoContent, nil, store.Rename(r.PathValue("id"), req.Name))
	})

	api.HandleFunc("POST /api/docs/{id}/resync", func(w http.ResponseWriter, r *http.Request) {
		respond(w, http.StatusNoContent, nil, store.Resync(r.PathValue("id")))
	})

	api.HandleFunc("DELETE /api/docs/{id}", func(w http.ResponseWriter, r *http.Request) {
		respond(w, http.StatusNoContent, nil, store.Decohere(r.PathValue("id")))
	})

	root := http.NewServeMux()
	root.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	root.Handle("/api/", requireToken(token, validIDs(api)))
	return logRequests(root)
}

func requireToken(token string, next http.Handler) http.Handler {
	expected := []byte("Bearer " + token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := []byte(r.Header.Get("Authorization"))
		if subtle.ConstantTimeCompare(got, expected) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="entanglement"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// validIDs rejects path ids that could escape the data directory before any handler runs.
func validIDs(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/"), "/")
		if len(parts) >= 2 && !idPattern.MatchString(parts[1]) {
			writeError(w, invalid("invalid id"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeError(w, invalid("malformed request body"))
		return false
	}
	return true
}

func validName(name string) bool {
	return strings.TrimSpace(name) != "" && utf8.RuneCountInString(name) <= maxNameChars
}

func respond(w http.ResponseWriter, status int, body any, err error) {
	if err != nil {
		writeError(w, err)
		return
	}
	if status == http.StatusNoContent || body == nil {
		w.WriteHeader(status)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, err error) {
	var inv invalidError
	switch {
	case errors.Is(err, errNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, errGone):
		http.Error(w, err.Error(), http.StatusGone)
	case errors.As(err, &inv):
		http.Error(w, inv.msg, http.StatusBadRequest)
	default:
		log.Printf("internal error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("%d %s %s %s", rec.status, r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
