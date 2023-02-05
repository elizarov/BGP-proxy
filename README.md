# BGP Proxy

A simple BGP protocol proxy that maintains a single uplink connection and forwards prefixes from the uplink
to any number of downstream connections. It does not implement the full BGP protocol by treats BGP as a transfer
protocol for dynamic set of IP address prefixes. The only information that is maintained about an IP address prefix 
and forwarded downstream is a set of BGP community tags it has. All the other BGP attributes coming from uplink
are ignored and replaced with the information about a local autonomous system that the proxy is configured to represent. 

## Relevant standards

* [RFC4271](https://www.rfc-editor.org/rfc/rfc4271) A Border Gateway Protocol 4 (BGP-4)
* [RFC1997](https://www.rfc-editor.org/rfc/rfc1997) BGP Communities Attribute
