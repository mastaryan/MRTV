#!/usr/bin/env python3
"""
MR TV 2 (2.0.6): builds the Live TV category map (mrtv2-categories.json) from a panel's get_live_categories export.

    python3 make_categories.py livecats.json out.json

The output is bundled in app/src/main/assets/mrtv2-categories.json and uploaded to https://watchmrtv.com/mrtv2-categories.json.
It holds: the country table (name, flag), the tag table for unknown future categories, label translations, topic rules,
one explicit entry per known category (country, clean English label, topic icon, order) and the virtual ESPN group.
No credentials or addresses are read or written.
"""
import json, re, sys, unicodedata

COUNTRIES = [
    # id, name, flag (ISO 3166 alpha-2, lower case; None = icon instead of a flag)
    ("US", "United States", "us"), ("WORLD", "International", None),
    ("AF", "Afghanistan", "af"), ("AL", "Albania", "al"), ("DZ", "Algeria", "dz"), ("ARAB", "Arabic (MENA)", None),
    ("AR", "Argentina", "ar"), ("AM", "Armenia", "am"), ("AU", "Australia", "au"), ("AT", "Austria", "at"),
    ("AZ", "Azerbaijan", "az"), ("BH", "Bahrain", "bh"), ("BD", "Bangladesh", "bd"), ("BY", "Belarus", "by"),
    ("BE", "Belgium", "be"), ("BO", "Bolivia", "bo"), ("BA", "Bosnia & Herzegovina", "ba"), ("BALKANS", "Balkans", None),
    ("BR", "Brazil", "br"), ("BG", "Bulgaria", "bg"), ("CM", "Cameroon", "cm"), ("CA", "Canada", "ca"),
    ("CARIB", "Caribbean", None), ("CL", "Chile", "cl"), ("CO", "Colombia", "co"), ("CG", "Congo & Gabon", "cg"),
    ("CR", "Costa Rica", "cr"), ("HR", "Croatia", "hr"), ("CY", "Cyprus", "cy"), ("CZ", "Czechia", "cz"),
    ("DK", "Denmark", "dk"), ("DO", "Dominican Republic", "do"), ("EC", "Ecuador", "ec"), ("EG", "Egypt", "eg"),
    ("SV", "El Salvador", "sv"), ("FI", "Finland", "fi"), ("FR", "France", "fr"), ("GE", "Georgia", "ge"),
    ("DE", "Germany", "de"), ("GH", "Ghana", "gh"), ("GR", "Greece", "gr"), ("GT", "Guatemala", "gt"),
    ("HT", "Haiti", "ht"), ("HN", "Honduras", "hn"), ("HK", "Hong Kong", "hk"), ("HU", "Hungary", "hu"),
    ("IN", "India", "in"), ("ID", "Indonesia", "id"), ("IR", "Iran", "ir"), ("IQ", "Iraq", "iq"), ("IE", "Ireland", "ie"),
    ("IL", "Israel", "il"), ("IT", "Italy", "it"), ("JP", "Japan", "jp"), ("JO", "Jordan", "jo"), ("XK", "Kosovo", "xk"),
    ("KURD", "Kurdistan", None), ("KW", "Kuwait", "kw"), ("LATAM", "Latin America", None), ("LV", "Latvia", "lv"),
    ("LB", "Lebanon", "lb"), ("LY", "Libya", "ly"), ("LT", "Lithuania", "lt"), ("MY", "Malaysia", "my"),
    ("ML", "Mali", "ml"), ("MT", "Malta", "mt"), ("MR", "Mauritania", "mr"), ("MX", "Mexico", "mx"),
    ("ME", "Montenegro", "me"), ("MA", "Morocco", "ma"), ("NP", "Nepal", "np"), ("NL", "Netherlands", "nl"),
    ("NZ", "New Zealand", "nz"), ("NI", "Nicaragua", "ni"), ("NG", "Nigeria", "ng"), ("MK", "North Macedonia", "mk"),
    ("NO", "Norway", "no"), ("OM", "Oman", "om"), ("PK", "Pakistan", "pk"), ("PS", "Palestine", "ps"),
    ("PA", "Panama", "pa"), ("PY", "Paraguay", "py"), ("PE", "Peru", "pe"), ("PH", "Philippines", "ph"),
    ("PL", "Poland", "pl"), ("PT", "Portugal", "pt"), ("QA", "Qatar", "qa"), ("RO", "Romania", "ro"),
    ("RU", "Russia", "ru"), ("SA", "Saudi Arabia", "sa"), ("SN", "Senegal", "sn"), ("RS", "Serbia", "rs"),
    ("SI", "Slovenia", "si"), ("SO", "Somalia", "so"), ("KR", "South Korea", "kr"), ("ES", "Spain", "es"),
    ("LK", "Sri Lanka", "lk"), ("SD", "Sudan", "sd"), ("SR", "Suriname", "sr"), ("SE", "Sweden", "se"),
    ("CH", "Switzerland", "ch"), ("SY", "Syria", "sy"), ("TH", "Thailand", "th"), ("TN", "Tunisia", "tn"),
    ("TR", "Turkey", "tr"), ("AE", "United Arab Emirates", "ae"), ("GB", "United Kingdom", "gb"), ("UA", "Ukraine", "ua"),
    ("UY", "Uruguay", "uy"), ("VN", "Vietnam", "vn"), ("YE", "Yemen", "ye"), ("AFRICA", "Africa", None),
    ("ADULT", "Adults", None),
]
CNAME = {c[0]: c[1] for c in COUNTRIES}

# Provider tag -> country or region, for categories whose name does not say the country
TAGS = {"NA": "US", "US": "US", "USA": "US", "EN": "US", "UK": "GB", "GB": "GB", "IE": "IE", "AU": "AU", "NZ": "NZ",
        "NL": "NL", "ES": "ES", "DE": "DE", "IT": "IT", "PT": "PT", "PL": "PL", "GR": "GR", "FR": "FR", "TH": "TH",
        "AZ": "AZ", "MT": "MT", "ALB": "AL", "BLN": "BALKANS", "AR": "ARAB", "AF": "AFRICA", "AS": "WORLD",
        "EU": "WORLD", "AM": "LATAM", "SA": "LATAM", "TR": "TR", "CA": "CA", "MX": "MX", "BR": "BR", "IN": "IN",
        "BE": "BE", "CH": "CH", "AT": "AT", "SE": "SE", "DK": "DK", "NO": "NO", "FI": "FI", "RO": "RO", "BG": "BG",
        "HU": "HU", "CZ": "CZ", "RU": "RU", "UA": "UA", "IL": "IL", "IR": "IR", "PK": "PK", "4K": "WORLD"}

# Country words at the start of a category name ("|AM| MEXICO", "|EU| SWEDEN HD", "|AR| EGYPT")
WORDS = {
    "USA": "US", "UNITED STATES": "US", "CANADA": "CA", "MEXICO": "MX", "AUSTRALIA": "AU", "IRELAND": "IE",
    "NEW ZEALAND": "NZ", "LATVIA": "LV", "HAITI": "HT", "NEDERLAND": "NL", "NETHERLANDS": "NL", "ESPAÑA": "ES",
    "SPAIN": "ES", "AUSTRIA": "AT", "SWISS": "CH", "SWITZERLAND": "CH", "POLAND": "PL", "ISRAEL": "IL",
    "ROMANIA": "RO", "RUSSIA": "RU", "BELARUS": "BY", "LITHUANIA": "LT", "BULGARIA": "BG", "HUNGARY": "HU",
    "DOMINICANA REP": "DO", "DOMINICAN REPUBLIC": "DO", "HONDURAS": "HN", "COLOMBIA": "CO", "ARGENTINA": "AR",
    "BOLIVIA": "BO", "CHILE": "CL", "GUATEMALA": "GT", "ECUADOR": "EC", "EL SALVADOR": "SV", "PANAMA": "PA",
    "PARAGUAY": "PY", "PERU": "PE", "URUGUAY": "UY", "COSTA RICA": "CR", "NICARAGUA": "NI", "UKRAINE": "UA",
    "BRAZIL": "BR", "IRAN": "IR", "INDIA": "IN", "PAKISTAN": "PK", "NEPAL": "NP", "SRILANKA": "LK",
    "SRI LANKA": "LK", "AFGHANISTAN": "AF", "SWEDEN": "SE", "SW": "SE", "DENMARK": "DK", "NORWAY": "NO",
    "FINLAND": "FI", "ARMENIA": "AM", "GEORGIE": "GE", "GEORGIA": "GE", "VIETNAM": "VN", "HONGKONG": "HK",
    "HONG KONG": "HK", "PHILIPPINES": "PH", "SOUTH KOREA": "KR", "CYPRUS": "CY", "MALAYSIA": "MY",
    "SURINAME": "SR", "THAILANDE": "TH", "THAILAND": "TH", "AZERBAYCAN": "AZ", "AZERBAIJAN": "AZ", "JAPAN": "JP",
    "MALTA": "MT", "INDONESIA": "ID", "FRANCE": "FR", "FR": "FR", "BELGIQUE": "BE", "BELGIUM": "BE", "BE": "BE",
    "CZECH": "CZ", "KOSOVO": "XK", "SERBIA": "RS", "CROATIA": "HR", "BOSNIA": "BA", "SLOVENIA": "SI",
    "MACEDONIA": "MK", "MONTENEGRO": "ME", "TURKEY": "TR", "KURDISTAN": "KURD", "MOROCCO": "MA", "ALGERIA": "DZ",
    "TUNISIA": "TN", "EGYPT": "EG", "LEBANON": "LB", "UAE": "AE", "MAURITANIA": "MR", "LIBYA": "LY", "OMAN": "OM",
    "BAHRAIN": "BH", "JORDAN": "JO", "YEMEN": "YE", "SAOUDI": "SA", "SAUDI": "SA", "SYRIA": "SY", "IRAQ": "IQ",
    "SUDAN": "SD", "PALESTINE": "PS", "QATAR": "QA", "KUWAIT": "KW", "SOMALIA": "SO", "SENEGAL": "SN",
    "CONGO/GABON": "CG", "CAMEROON": "CM", "GHANA": "GH", "NIGERIA": "NG", "MALI": "ML", "GERMANY": "DE",
    "ITALY": "IT", "PORTUGAL": "PT", "GREECE": "GR", "ALBANIA": "AL", "CARRIBEAN": "CARIB", "CARIBBEAN": "CARIB",
    "LATINO": "LATAM", "BANGLA": "BD",
}

# Whole labels (after the country word is removed) -> English
LABELS = {
    "": "General", "GENERAL": "General", "GÉNÉRAL": "General", "ALLGEMEINES": "General", "ALGEMEEN": "General",
    "GENERALE": "General", "EMGERAL": "General", "OGÓLNE": "General", "Γενικά": "General", "ULUSAL": "National",
    "HD": "General", "FHD": "General", "4K ULTRA": "4K Ultra", "HEVC": "HEVC", "FR": "French", "VL": "Flemish",
    "WL": "Walloon", "NEWS": "News", "INFO": "News", "INFORMACJE": "News", "SPORTS": "Sports", "SPORT": "Sports",
    "DEPORTES": "Sports", "DESPORTO": "Sports", "SPORTOWE": "Sports", "Αθλητικά": "Sports", "SPOR": "Sports",
    "KIDS": "Kids", "KINDER": "Kids", "KINDEREN": "Kids", "NIÑOS": "Kids", "BAMBINI": "Kids", "CRIANÇAS": "Kids",
    "Παιδικά": "Kids", "COCUK": "Kids", "ENFANTS": "Kids", "CHILDREN": "Kids", "MOVIES": "Movies",
    "CINEMA": "Movies", "FILMOWE": "Movies", "κινηματογράφος": "Movies", "SINEMA": "Movies",
    "FILMS EN SERIES": "Movies & Series", "FILMES E SÉRIES": "Movies & Series", "SKY FILM UND SERIE": "Sky Movies & Series",
    "M.PELICULAS": "M+ Movies", "ENTERTAINMENT": "Entertainment", "UNTERHALTUNG": "Entertainment",
    "DOCUMENTARY": "Documentary", "DOCUMENTALES": "Documentary", "DOCUMENTARIO": "Documentary",
    "DOCUMENTARIOS": "Documentary", "DOKUMENTALNE": "Documentary", "Ντοκιμαντέρ": "Documentary",
    "DOCUMENTAIRE": "Documentary", "MUSIC": "Music", "MUZIEK": "Music", "MUSIQUE": "Music", "Μουσικά": "Music",
    "MUZYKA | DZIECI": "Music & Kids", "NATUUR": "Nature", "NATUR": "Nature", "REGIONAAL": "Regional",
    "REGIONAL": "Regional", "BUITENLAND": "Abroad", "INTERNACIONAL": "International", "Κυπριακά": "Cypriot",
    "DIGER": "Other", "ISLAMIC": "Islamic", "CHRISTIAN": "Christian", "LOCALS": "Locals",
}

TOPIC_ORDER = {"general": 10, "network": 20, "news": 30, "sports": 40, "league": 50, "streaming": 70, "kids": 80,
               "movies": 81, "entertainment": 82, "documentary": 83, "nature": 84, "music": 85, "regional": 86,
               "language": 88, "religious": 89, "ppv": 100, "replay": 105, "uhd": 108, "twentyfour": 110, "adult": 200}

# Topic rules for unknown categories, first match wins (applied to the upper-case name without the tag)
TOPICS = [
    ("adult", r"ADULT|XXX|\+18|FOR ADULTS"), ("twentyfour", r"24/7"), ("ppv", r"\bPPV\b|EVENT"),
    ("replay", r"REPLAY|REPETICI"), ("uhd", r"\b4K\b|\b8K\b|UHD|ᵁᴴᴰ"), ("news", r"NEWS|\bINFO|NOTICIAS|INFORMACJE"),
    ("kids", r"KIDS|KINDER|ENFANTS|NIÑOS|BAMBINI|CRIAN|CHILDREN|COCUK|Παιδικά"),
    ("league", r"\b(NBA|WNBA|NFL|NHL|MLB|MILB|MLS|NCAA|AHL|EPL|EFL|AFL|HOCKEY LEAGUE|LIGUE 1|LA ?LIGA|SOCCER|FOOTBALL)\b"),
    ("sports", r"SPORT|DEPORT|DESPORT|SPOR\b|Αθλητικά|BEIN|DAZN|ESPN|EUROSPORT|CRICKET|FORMULA"),
    ("movies", r"MOVIE|CINEMA|FILM|PELICUL|SINEMA|κινηματογράφος|BOX OFFICE"),
    ("documentary", r"DOCU|DOKU|Ντοκιμαντέρ|FACTUAL"), ("nature", r"NATU"),
    ("music", r"MUSIC|MUZI|MUSIQ|Μουσικά|MUZYKA"), ("religious", r"ISLAM|CHRIST|QURAN|RELIGI"),
    ("streaming", r"NETFLIX|HULU|HBO|MAX\b|PEACOCK|PARAMOUNT|PRIME|DISNEY|SHAHID|STARZ|OSN|APPLE"),
    ("entertainment", r"ENTERTAIN|UNTERHALT|DRAMA|COMEDY"), ("regional", r"REGIONA|LOCAL"),
]

# Explicit entries: raw name -> (country, label, topic, order). Order only where the default is not right.
US = {
    "USA GENERAL": ("General", "general", 10), "USA ABC": ("ABC", "network", 20), "USA CBS": ("CBS", "network", 21),
    "USA FOX": ("FOX", "network", 22), "USA NBC": ("NBC", "network", 23), "USA PBS": ("PBS", "network", 24),
    "USA CW & MY": ("CW & MyNetwork", "network", 25), "USA SPECTRUM NETWORK": ("Spectrum News", "news", 31),
    "USA NEWS": ("News", "news", 30), "USA SPORTS": ("Sports", "sports", 40), "USA NBCS": ("NBC Sports (regional)", "sports", 42),
    "USA NBA": ("NBA", "league", 50), "USA NFL": ("NFL", "league", 51), "USA NHL": ("NHL", "league", 52),
    "USA MLB": ("MLB", "league", 53), "USA MLS": ("MLS", "league", 54), "USA WNBA": ("WNBA", "league", 55),
    "USA NCAA": ("NCAA (college)", "league", 56), "USA MILB": ("MiLB (minor league baseball)", "league", 57),
    "HOCKEY LEAGUE": ("AHL Hockey", "league", 58), "USA ESPN+": ("ESPN+ events", "sports", 60),
    "USA ESPN PLAY": ("ESPN Play events", "sports", 61), "USA FLO": ("FloSports", "sports", 62),
    "USA PEACOCK NETWORK": ("Peacock", "streaming", 70), "USA PARAMOUNT+": ("Paramount+", "streaming", 71),
    "USA HBO MAX NETWORK": ("HBO Max", "streaming", 72), "USA HULU NETWORK": ("Hulu", "streaming", 73),
    "USA NETFLIX PPV": ("Netflix live events", "streaming", 74), "USA KIDS": ("Kids", "kids", 80),
    "USA MOVIES": ("Movies", "movies", 81), "TELEMUNDO": ("Telemundo", "language", 90),
    "UNIVISION": ("Univision", "language", 91), "TUDN SPORTS": ("TUDN", "language", 92),
    "PPV LIVE EVENT": ("PPV live events", "ppv", 100), "USA DAZN PPV": ("DAZN PPV", "ppv", 101),
    "USA B1G+ PPV": ("Big Ten+ PPV", "ppv", 102), "USA FLO PPV": ("FloSports PPV", "ppv", 103),
    "24/7 ENGLISH": ("24/7", "twentyfour", 110), "24/7 ENGLISH 4K": ("24/7 in 4K", "twentyfour", 111),
}
EXPLICIT = {
    "4K UHD": ("WORLD", "4K UHD", "uhd", 10), "4K UHD RELAX": ("WORLD", "4K Relax", "uhd", 11),
    "FORMULA 1 + MOTO GP": ("WORLD", "Formula 1 & MotoGP", "sports", 20), "WORLD CRICKET": ("WORLD", "Cricket", "sports", 21),
    "FOR ADULTS": ("ADULT", "Adults", "adult", 200), "JAPAN": ("JP", "General", "general", 10),
    "|AU| STAN PPV": ("AU", "Stan PPV", "ppv", None), "|AU| AFL PPV": ("AU", "AFL PPV", "ppv", None),
    "|AU| AU ESPN PLAY PPV": ("AU", "ESPN Play PPV", "ppv", None),
    "|UK| ITVX": ("GB", "ITVX", "streaming", None), "|UK| PSF PPV": ("GB", "PSF PPV", "ppv", None),
    "|UK| EPL+ PPV": ("GB", "Premier League+ PPV", "ppv", None), "|UK| EPL SOCCER": ("GB", "Premier League", "league", None),
    "|UK| NATIONAL LEAGUE": ("GB", "National League", "league", None), "|UK| EFL SOCCER": ("GB", "EFL", "league", None),
    "|UK| AMAZON PRIME": ("GB", "Amazon Prime", "streaming", None), "|UK| DISCOVERY+ PPV": ("GB", "Discovery+ PPV", "ppv", None),
    "|UK| DAZN PPV": ("GB", "DAZN PPV", "ppv", None), "|UK| NETFLIX PPV": ("GB", "Netflix live events", "streaming", None),
    "|UK| SKY STORE": ("GB", "Sky Store", "movies", None), "|UK| VIAPLAY PPV": ("GB", "Viaplay PPV", "ppv", None),
    "|UK| 24/7 KIDS": ("GB", "24/7 Kids", "twentyfour", None), "|UK| DISCOVERY+": ("GB", "Discovery+", "documentary", None),
    "|UK| 24/7 FLEX": ("GB", "24/7", "twentyfour", None), "|UK| PPV EVENTS": ("GB", "PPV events", "ppv", None),
    "|UK| WORLD SPORTS": ("GB", "World Sports", "sports", None), "|UK| CYMRU FOOTBALL PPV": ("GB", "Welsh Football PPV", "ppv", None),
    "|UK| SOCCER REPLAY": ("GB", "Soccer replays", "replay", None), "|UK| SCOTTISH FOOTBALL": ("GB", "Scottish Football", "league", None),
    "|UK| OTHER SPORTS": ("GB", "Other Sports", "sports", None), "|UK| SKY SPORT+": ("GB", "Sky Sport+", "sports", None),
    "|IE| IRELAND": ("IE", "General", "general", None), "|IE| CLUBBER PPV": ("IE", "Clubber PPV", "ppv", None),
    "|IE| SETANTA PPV": ("IE", "Setanta PPV", "ppv", None), "|IE| LOI PPV": ("IE", "League of Ireland PPV", "ppv", None),
    "|NA| CANADA FR": ("CA", "French", "language", None), "|AM| CANADA DAZN PPV": ("CA", "DAZN PPV", "ppv", None),
    "|AM| AHL PPV": ("CA", "AHL PPV", "ppv", None), "|SA| CARRIBEAN": ("CARIB", "General", "general", None),
    "|NL| VIAPLAY SPORT": ("NL", "Viaplay Sport", "sports", None), "|NL| ESPN PPV": ("NL", "ESPN PPV", "ppv", None),
    "|EU| NEDERLAND 4K ULTRA": ("NL", "4K Ultra", "uhd", None),
    "|ES| REPETICIÓN DE FÚTBOL": ("ES", "Soccer replays", "replay", None), "|ES| ESPAÑA HEVC": ("ES", "HEVC", "general", 11),
    "|ES| TIVIFY": ("ES", "Tivify", "streaming", None), "|ES| ESPAÑA DAZN PPV": ("ES", "DAZN PPV", "ppv", None),
    "|ES| M+ LIGA DE CAMPEONES": ("ES", "M+ Champions League", "league", None), "|ES| M+ BALONCESTO": ("ES", "M+ Basketball", "league", None),
    "|ES| FEF TV EVENTOS": ("ES", "FEF TV events", "ppv", None), "|ES| 24/7 SPANISH": ("ES", "24/7", "twentyfour", None),
    "|ES| M.PELICULAS": ("ES", "M+ Movies", "movies", None), "|ES| LOCALS": ("ES", "Locals", "regional", None),
    "|DE| AMAZON PRIME WIMBLEDON PPV": ("DE", "Amazon Prime Wimbledon PPV", "ppv", None),
    "|DE| SPORT DEUTSCHLAND PPV": ("DE", "Sport Deutschland PPV", "ppv", None), "|DE| DAZN PRIME": ("DE", "DAZN & Prime", "sports", None),
    "|DE| DYN NETWORK": ("DE", "Dyn", "sports", None), "|DE| 4K+ PAKET": ("DE", "4K+ package", "uhd", None),
    "|DE| 24/7 GERMANY": ("DE", "24/7", "twentyfour", None), "|EU| AUSTRIA HD": ("AT", "General", "general", None),
    "|EU| SWISS HD": ("CH", "General", "general", None), "|PL| POLAND FHD": ("PL", "General (FHD)", "general", 11),
    "|PL| NA HD": ("PL", "Polish TV abroad", "general", 12), "|PL| WALKI PPV": ("PL", "Fight PPV", "ppv", None),
    "|PL| VIAPLAY SPORT": ("PL", "Viaplay Sport", "sports", None), "|PL| MAX PPV": ("PL", "Max PPV", "ppv", None),
    "|GR| Κυπριακά": ("GR", "Cypriot", "regional", None),
    "|EU| SWEDEN VIAPLAY PPV": ("SE", "Viaplay PPV", "ppv", None), "|EU| SW MAX PPV": ("SE", "Max PPV", "ppv", None),
    "|AS| INDIA - EU": ("IN", "General (Europe)", "general", 11), "|AS| TAMIL": ("IN", "Tamil", "language", None),
    "|AS| SPORTS": ("IN", "Sports", "sports", None), "|AS| ENGLISH": ("IN", "English", "language", None),
    "|AS| BANGLA": ("BD", "General", "general", None), "|AS| PUNJABI": ("IN", "Punjabi", "language", None),
    "|AS| GUJARATI": ("IN", "Gujarati", "language", None), "|AS| MALAYALAM": ("IN", "Malayalam", "language", None),
    "|AS| MARATHI": ("IN", "Marathi", "language", None), "|AS| TELUGU": ("IN", "Telugu", "language", None),
    "|AS| KANNADA": ("IN", "Kannada", "language", None), "|AS| BHOJPURI": ("IN", "Bhojpuri", "language", None),
    "|AS| ASSAM": ("IN", "Assamese", "language", None),
    "|EU| DAZN LA LIGA": ("FR", "DAZN LaLiga", "sports", None), "|EU| DISNEY+ PPV": ("FR", "Disney+ PPV", "ppv", None),
    "|EU| FRANCE HEVC": ("FR", "HEVC", "general", 11), "|EU| L'EQUIPE LIVE": ("FR", "L'Équipe Live", "sports", None),
    "|FR| EUROSPORTS 360": ("FR", "Eurosport 360", "sports", None), "|EU| LIGUE 1+ VIP": ("FR", "Ligue 1+ VIP", "league", None),
    "|EU| LIGUE 1+": ("FR", "Ligue 1+", "league", None), "|EU| FR SOCCER PPV": ("FR", "Soccer PPV", "ppv", None),
    "|EU| FRANCE DAZN PPV": ("FR", "DAZN PPV", "ppv", None), "|EU| FRANCE CANAL+ LIVE": ("FR", "Canal+ Live", "ppv", None),
    "|EU| LE MEILLEUR DES FILMS": ("FR", "Best Movies", "movies", None), "|EU| FRANCE NETFLIX 24/7": ("FR", "Netflix 24/7", "twentyfour", None),
    "|EU| FRANCE CARRIBEAN": ("FR", "Caribbean", "regional", None), "|EU| FRANCE 4K": ("FR", "4K", "uhd", None),
    "|EU| BE DAZN PPV VIP": ("BE", "DAZN PPV VIP", "ppv", None), "|EU| BE DAZN PPV": ("BE", "DAZN PPV", "ppv", None),
    "|EU| BELGIUM VL": ("BE", "Flemish", "language", None), "|EU| BELGIUM WL": ("BE", "Walloon (French)", "language", None),
    "|ALB| KOSOVO": ("XK", "General", "general", None), "|BLN| SPORTS": ("BALKANS", "Sports", "sports", None),
    "|AS| TURKEY | ULUSAL": ("TR", "National", "general", None), "|AS| TURKEY | DIGER": ("TR", "Other", "entertainment", None),
    "|AS| TURKEY | TABII SPOR": ("TR", "Tabii Sports", "sports", None), "|AS| TURKEY | REDBOX": ("TR", "Redbox", "movies", None),
    "|AF| C+ AFRIQUE": ("AFRICA", "Canal+ Afrique", "general", None), "|AF| DSTV AFRIQUE": ("AFRICA", "DStv", "general", None),
    "|AF| AZAM NETWORK": ("AFRICA", "Azam", "general", None), "|AF| AFRIQUE OTHERS": ("AFRICA", "Other channels", "general", None),
    "|AR| ISLAMIC": ("ARAB", "Islamic", "religious", None), "|AR| CHRISTIAN": ("ARAB", "Christian", "religious", None),
    "|AR| ARABIC KIDS": ("ARAB", "Kids", "kids", None), "|AR| ARABIC NEWS & FACTUAL": ("ARAB", "News & Factual", "news", None),
    "|AR| ARABIC FULL SD": ("ARAB", "All channels (SD)", "general", 12), "|AR| SPORT FULL SD": ("ARAB", "Sports (SD)", "sports", None),
    "|AR| ARABIC VIP": ("ARAB", "VIP", "general", 11), "|AR| SPORT": ("ARAB", "Sports", "sports", None),
    "|AR| MUSIC": ("ARAB", "Music", "music", None), "|AR| ROTANA | ART": ("ARAB", "Rotana & ART", "entertainment", None),
    "|AR| MBC": ("ARAB", "MBC", "entertainment", None), "|AR| OSN": ("ARAB", "OSN", "streaming", None),
    "|AR| AL FAJER": ("ARAB", "Al Fajer", "entertainment", None), "|AR| CHEF OMAR": ("ARAB", "Chef Omar", "entertainment", None),
    "|AR| BEIN REPLAY": ("ARAB", "beIN replays", "replay", None), "|AR| DAZN PPV": ("ARAB", "DAZN PPV", "ppv", None),
    "|AR| SHAHID PPV": ("ARAB", "Shahid PPV", "ppv", None), "|AR| PREMIUM PLAY": ("ARAB", "Premium Play", "movies", None),
    "|AR| BEIN PREMIUM PLAY": ("ARAB", "beIN Premium Play", "movies", None), "|AR| BEIN ENTERTAINMENT": ("ARAB", "beIN Entertainment", "entertainment", None),
    "|AR| ABU DHABI SPORTS": ("AE", "Abu Dhabi Sports", "sports", None), "|AR| SAOUDI": ("SA", "General", "general", None),
}
# Arabic sports and streaming bundles with plain English names
for n in ["BEIN SPORTS AFC", "BEIN SPORTS 8K", "BEIN SPORTS G", "BEIN SPORTS NX", "BEIN SPORTS 4K", "BEIN SPORTS ᵁᴴᴰ",
          "BEIN SPORTS (UK)", "SHAHID SPORTS LIVE", "SHASHA SPORT", "ALWAN SPORT", "STARZPLAY / AD SPORTS", "THMANYAH"]:
    lbl = (n.title().replace("Bein", "beIN").replace("Ᵽ", "").replace("Nx", "NX").replace("Afc", "AFC").replace("8K", "8K")
           .replace("ᵁᴴᴰ", "UHD").replace("(Uk)", "(UK)").replace("Starzplay / Ad Sports", "STARZPLAY & AD Sports"))
    EXPLICIT["|AR| " + n] = ("ARAB", lbl, "sports", None)
for n, l in [("WATCHBOX 4K", "Watchbox 4K"), ("ACTORS 4K", "Actors 4K"), ("ROYA", "Roya"), ("WEYYAK", "Weyyak"),
             ("MAJESTIC 4K", "Majestic 4K"), ("NETFLIX", "Netflix"), ("SHAHID. CINEMA", "Shahid Cinema"),
             ("GOBX GROUP", "GOBX"), ("SHAHID. VIP", "Shahid VIP"), ("DRAMA PLAY", "Drama Play"),
             ("COMEDY PLAY", "Comedy Play"), ("WATCH IT", "Watch It"), ("STARZ PLAY", "STARZPLAY"), ("ALWAN", "Alwan"),
             ("STARZ MOVIES", "Starz Movies"), ("HOME CINEMA", "Home Cinema"), ("KTV MOVIES", "KTV Movies"),
             ("AMAZON PRIME", "Amazon Prime"), ("HBO", "HBO"), ("HULU", "Hulu"), ("TOD", "TOD"), ("VIVO", "Vivo")]:
    EXPLICIT["|AR| " + n] = ("ARAB", l, "streaming", None)


def norm(s):
    return re.sub(r"\s+", " ", s.replace("\u00a0", " ")).strip()


TAG_RE = re.compile(r"^\|\s*([A-Z0-9]{2,4})\s*\|\s*(.*)$")


def topic_of(rest):
    for t, rx in TOPICS:
        if re.search(rx, rest, re.I):
            return t
    return "general"


def nice(s):
    if s in LABELS:
        return LABELS[s]
    if not s.isascii():
        return s
    small = {"AND": "and", "OF": "of", "DE": "de"}
    keep = {"TV", "HD", "FHD", "UHD", "SD", "PPV", "VIP", "HEVC", "4K", "8K", "MBC", "OSN", "ART", "DAZN"}
    return " ".join(w if w in keep else small.get(w, w.capitalize()) for w in s.split(" "))


def parse(raw):
    n = norm(raw)
    if n in EXPLICIT:
        return EXPLICIT[n]
    m = TAG_RE.match(n)
    tag, rest = (m.group(1), m.group(2)) if m else ("", n)
    if tag in ("NA", "EN") and rest in US:
        lbl, t, o = US[rest]
        return ("US", lbl, t, o)
    up = rest.upper()
    # "TURKEY | SPOR" style
    country = None
    for w in sorted(WORDS, key=len, reverse=True):
        if up == w or up.startswith(w + " ") or up.startswith(w + " | ") or up.startswith(w + "|"):
            country = WORDS[w]
            rest = rest[len(w):].strip(" |-")
            break
    if country is None:
        country = TAGS.get(tag, "WORLD")
    lbl = nice(rest.upper() if rest.isascii() else rest)
    t = topic_of(rest) if rest else "general"
    if lbl == "General":
        t = "general"
    return (country, lbl, t, None)


def main(src, out):
    cats = json.load(open(src, encoding="utf-8"))
    entries = {}
    for c in cats:
        raw = c["category_name"]
        cc, lbl, t, o = parse(raw)
        assert cc in CNAME, (raw, cc)
        e = {"c": cc, "n": lbl, "t": t}
        if o is not None:
            e["o"] = o
        entries[norm(raw).upper()] = e
    doc = {
        "version": 1,
        "updated": "2026-11-10",
        "note": "MR TV 2 Live TV categories: country, clean English label, icon topic and order per provider category. "
                "Unknown categories are parsed from their |XX| tag. Upload to https://watchmrtv.com/mrtv2-categories.json.",
        "countries": [dict(id=i, name=nm, **({"flag": f} if f else {})) for i, nm, f in COUNTRIES],
        "first": ["US", "WORLD"], "last": ["ADULT"],
        "tags": TAGS,
        "words": WORDS,
        "labels": {k: v for k, v in LABELS.items() if k},
        "topicOrder": TOPIC_ORDER,
        "topics": [{"t": t, "re": rx} for t, rx in TOPICS],
        "categories": entries,
        "virtual": [{
            "id": "ESPN", "c": "US", "n": "ESPN", "t": "sports", "o": 41,
            "from": ["US"],
            "include": r"^(?:[A-Z]{2,3}\s*[-:|]\s*)?(?:ESPN(?:\s?2|\s?U|\s?NEWS|EWS|\s?DEPORTES)?|SEC\s?NETWORK|SECN|SEC\s?NETWORK\s?PLUS|ACC\s?NETWORK|ACCN|LONGHORN\s?NETWORK)\b",
            "exclude": r"ESPN\s?\+|ESPN\s?PLAY|\bPPV\b|EVENT|\(ESPN",
            "excludeCats": ["USA ESPN+", "USA ESPN PLAY"]
        }],
    }
    json.dump(doc, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1, sort_keys=False)
    print(len(entries), "categories")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
