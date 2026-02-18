package main

import (
	"context"
	"errors"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func main() {}

func handleReturn(w http.ResponseWriter, r *http.Request) {
	returnURL := r.URL.Query().Get("return")
	tx := r.URL.Query().Get("tx")
	if tx == "" {
		http.Error(w, "missing tx", http.StatusBadRequest)
		return
	}

	if returnURL == "" || !strings.HasPrefix(returnURL, "/") {
		returnURL = "/billing"
	}

	http.Redirect(w, r, returnURL, http.StatusFound)
}

func generateThumbnail(ctx context.Context, pdfID string) ([]byte, error) {
	in := filepath.Join("/srv/invoices/", pdfID+".pdf")
	out := filepath.Join("/srv/invoices/thumbs/", pdfID+".png")

	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, "gs",
		"-dSAFER",
		"-sDEVICE=pngalpha",
		"-o", out,
		"-r144",
		in,
	)

	b, err := cmd.CombinedOutput()
	if err != nil {
		return nil, errors.New(string(b))
	}
	return []byte("ok"), nil
}
