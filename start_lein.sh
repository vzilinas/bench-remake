#!/bin/bash
{
    sleep 2m
    kill $$
} &

cd data
lein run -n --configPath localConf.yaml
lein run -r -t 1000 --configPath localConf.yaml
