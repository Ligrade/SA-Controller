#!/bin/bash
# Double-clic : lance le récepteur SA Controller.
cd "$(dirname "$0")"
exec python3 sa-recepteur.py "$@"
