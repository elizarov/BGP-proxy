# BGP Proxy

A simple BGP protocol proxy that maintains one or several BGP uplink connections and forwards IP prefixes from the uplink
to any number of downstream connections. BGP protocol is served over TPC port 179. 
It does not implement the full BGP protocol but treats BGP as a transfer protocol for a dynamic set of IP address prefixes. 
The only information that is maintained about an IP address prefix and forwarded downstream is a set of BGP community 
tags it has. All the other BGP attributes coming from uplink are ignored and are replaced with the information about 
a local autonomous system that the proxy is configured to represent. 

## Configuration

Example config file is in [bgp-proxy.cfg](bgp-proxy.cfg) file. It configures a composition of various sources 
of IP address prefix sets and is processes from top to bottom. Each line starts with `+` to add a set of IPs or
`-` to remove a set of IPs. The supported IP address sources are the following:

* `bgp:<host>` another BGP server as a source of IP address prefixes.
* `<ip-prefix>/<bits>` an ip address mask.
* `<ip-address>` a literal numeric IPv4 address.
* `<host-name>` a host name. It gets resolved periodically via DNS and the IP addresses it resolves to are used.
* `*.<host-name-suffix>` matches all host names that end with a specified suffix. It is supported only in DNS proxy mode. 

Configuration file gets automatically reloaded from disk when its change is detected. No restart is needed.

## DNS proxy mode

When launched with DNS proxy mode (see [command line parameters](#command-line-parameters)) it additionally works
as a DNS proxy, accepting DNS requests on port 53 over both UDP and TCP. It forwards all supported requrests to the
downstream nameservers and returns them back without changes. It caches successful results of `A` queries. 
The cache is used to support `*.<dns-suffix>` wildcard as a source of IP  address prefixes. 
All the matching DNS queries that go via the DNS proxy get their resolved IP addresses added.

The cache is also used to respond to further `A` queries until the records expire. The DNS names from the 
configuration are continuously resolved on expiration and saved to cache, thus the cache for those names is 
always ready. This guarantees that the DNS query results for those host names are consistent with the IP addresses 
that are reported via BGP. 

The only supported DNS types that can go though the proxy are: 
`A`, `NS`, `CNAME`, `SOA`, `PTR`, `TXT`, `AAAA`, `SRV`, `SVCB`, `HTTPS`. 
Among those, only `A` gets interpreted and cached. 

## DNS proxy logs

DNS proxy logs successful responses on the IP addresses resolution (`A` queries) that go through it in the following way:

```
[Dns]: <proto>/<src>: <host-name>: <ip-addresses> TTL:<ttl> {<time> ms}<suffix>
```

* `<proto>` is the protocol over which the request came: `UDP` or `TCP`.
* `<src>` the requesting host and port.
* `<host-name>` the requested host name to resolve.
* `<ttl>` resulting record TTL is seconds.
* `<time>` how long it took for upstream nameserver to respond (in ms).
* `<suffix>` is `*` when it had updated any wildcard configuration records.
* There is no time nor suffix, when the entry was served from cache.

## Command line parameters

```
Usage: BGPProxy <local-address> <local-autonomous-system> <config-file> [<nameservers>]
```

* `<local-address>` and `<local-autonomous-system>` are reported via BGP protocol.
* `<config-file>` is a path to configuration files.
* `<nameserver>` IP addresses of one or two nameservers. They are required to activate DNS proxy mode.

## Additional notes

Every resolved IP address from DNS is kept in the resulting set of IP addresses for **1 hour**. 
Even if resolved set of IP addresses for a particular DNS name changes often, this is how long each individual IP address
is being kept. This also applies to IP addresses that correspond to wildcard DNS names that are discovered via DNS proxy. 

Non-wildcard DNS named are resolved again as soon as their TTL expires, but at least **every minute**. 

There is **200 ms** delay in sending response to DNS queries that had updated any wildcard configuration record (these
are logged with `(*)` suffix) so that there is time for BGP status update to propagate before the DNS response is sent. 

## Relevant standards

* [RFC4271](https://www.rfc-editor.org/rfc/rfc4271) A Border Gateway Protocol 4 (BGP-4)
* [RFC1997](https://www.rfc-editor.org/rfc/rfc1997) BGP Communities Attribute
* [RFC1035](https://www.rfc-editor.org/rfc/rfc1035) Domain Names - Implementation and Specification

## Building and deploying

Build the Linux binary with

```bash
./gradlew linuxX64MainBinaries 
```

You'll find the compiled binary at 
[build/bin/linuxX64/bgpProxyReleaseExecutable/bgp-proxy.kexe](build/bin/linuxX64/bgpProxyReleaseExecutable/bgp-proxy.kexe).

To deploy on Ubuntu:

1. Copy `bgp-proxy.kexe` to your home directory.
2. Create `bgp-proxy.cfg` configuration file there (use the [example](bgp-proxy.cfg)).
3. Create `bgp-proxy.sh` shell script to run it.
 
Here is the example script assuming that your user is `root`, the home directory is `/root`, you'll use
autonomous system 64512 (any number from 64512 to 65534 comes from the private ASN range and can be safely used), 
and you're using Google DNS servers:

```bash
#!/bin/bash
/root/bgp-proxy.kexe <local-ip-address> 64512 /root/bgp-proxy.cfg 8.8.8.8 8.8.4.4
```

Then make it executable:

```bash
chmod +x bgp-proxy.sh
```

Create `/etc/systemd/system/bgp-proxy.service` to run to it as a service:

```
[Unit]
Description=BGP proxy service
After=network.target

[Service]
Type=simple
Restart=always
RestartSec=1
User=root
ExecStart=/root/bgp-proxy.sh
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=bgp-proxy

[Install]
WantedBy=multi-user.target
```

Enable and start the service:

```bash
systemctl enable bgp-proxy
systemctl start bgp-proxy
```

You can watch the log using:

```bash
journalctl -u bgp-proxy -f
```
