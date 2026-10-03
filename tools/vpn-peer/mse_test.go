package main

import (
	"io"
	"net"
	"testing"
	"time"

	"github.com/anacrolix/torrent/mse"
)

func TestEncryptedTorrentFixtureTransport(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(5 * time.Second))
	key := []byte("fixture-info-hash-20!")
	result := make(chan error, 1)
	go func() {
		stream, err := receiveTorrentStream(server, key)
		if err == nil {
			data := make([]byte, len("fixture"))
			_, err = io.ReadFull(stream, data)
			if err == nil && string(data) != "fixture" {
				err = io.ErrUnexpectedEOF
			}
			if err == nil {
				_, err = stream.Write([]byte("ok"))
			}
		}
		result <- err
	}()
	stream, method, err := mse.InitiateHandshake(client, key, []byte("fixture"), mse.CryptoMethodRC4)
	if err != nil {
		t.Fatal(err)
	}
	if method != mse.CryptoMethodRC4 {
		t.Fatal("Encrypted transport was not negotiated")
	}
	response := make([]byte, 2)
	if _, err = io.ReadFull(stream, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != "ok" {
		t.Fatal("Fixture transport did not round-trip")
	}
	if err = <-result; err != nil {
		t.Fatal(err)
	}
}
