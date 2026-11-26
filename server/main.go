// Entanglement server: keeps entangled Markdown documents in sync between observers.
//
// Configuration via environment:
//
//	ENTANGLEMENT_TOKEN  required, at least 24 characters: shared secret of all observers
//	ENTANGLEMENT_DATA   data directory (default ./data)
//	PORT                listen port (default 8080)
//
// `entanglement-server -healthcheck` probes the running server (used by Docker).
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const keepVersions = 50

func main() {
	healthcheck := flag.Bool("healthcheck", false, "probe the local server and exit")
	flag.Parse()

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	if *healthcheck {
		os.Exit(probe(port))
	}

	token := os.Getenv("ENTANGLEMENT_TOKEN")
	if len(token) < 24 {
		log.Fatal("ENTANGLEMENT_TOKEN must be set and at least 24 characters long")
	}
	dir := os.Getenv("ENTANGLEMENT_DATA")
	if dir == "" {
		dir = "data"
	}

	store, err := OpenStore(dir, keepVersions)
	if err != nil {
		log.Fatalf("opening data directory %s: %v", dir, err)
	}

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           NewHandler(store, token),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       2 * time.Minute,
	}

	go func() {
		log.Printf("Entanglement listening on :%s, data in %s", port, dir)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	log.Print("stopped")
}

func probe(port string) int {
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/health")
	if err != nil || resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
