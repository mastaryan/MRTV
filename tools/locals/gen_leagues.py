#!/usr/bin/env python3
"""Builds app/src/main/assets/locals/dma_leagues.json: US teams of the big leagues -> home Nielsen market (DMA code).
Only public facts (team name, home city). Canadian teams are left out (US locals only).
Entries whose home market is in flux are marked verify:true.
Usage: python3 tools/locals/gen_leagues.py app/src/main/assets/locals/dmas.json app/src/main/assets/locals/dma_leagues.json"""
import json, sys, datetime

# league -> [(abbr, full name, nickname, market name in dmas.json, verify note or None)]
T = {
 "NFL": [
  ("ARI","Arizona Cardinals","Cardinals","Phoenix",None),("ATL","Atlanta Falcons","Falcons","Atlanta",None),
  ("BAL","Baltimore Ravens","Ravens","Baltimore",None),("BUF","Buffalo Bills","Bills","Buffalo",None),
  ("CAR","Carolina Panthers","Panthers","Charlotte",None),("CHI","Chicago Bears","Bears","Chicago",None),
  ("CIN","Cincinnati Bengals","Bengals","Cincinnati",None),("CLE","Cleveland Browns","Browns","Cleveland–Akron",None),
  ("DAL","Dallas Cowboys","Cowboys","Dallas–Fort Worth",None),("DEN","Denver Broncos","Broncos","Denver",None),
  ("DET","Detroit Lions","Lions","Detroit",None),("GB","Green Bay Packers","Packers","Green Bay–Appleton",None),
  ("HOU","Houston Texans","Texans","Houston",None),("IND","Indianapolis Colts","Colts","Indianapolis",None),
  ("JAX","Jacksonville Jaguars","Jaguars","Jacksonville",None),("KC","Kansas City Chiefs","Chiefs","Kansas City",None),
  ("LV","Las Vegas Raiders","Raiders","Las Vegas",None),("LAC","Los Angeles Chargers","Chargers","Los Angeles",None),
  ("LAR","Los Angeles Rams","Rams","Los Angeles",None),("MIA","Miami Dolphins","Dolphins","Miami–Fort Lauderdale",None),
  ("MIN","Minnesota Vikings","Vikings","Minneapolis–St. Paul",None),("NE","New England Patriots","Patriots","Boston",None),
  ("NO","New Orleans Saints","Saints","New Orleans",None),("NYG","New York Giants","Giants","New York",None),
  ("NYJ","New York Jets","Jets","New York",None),("PHI","Philadelphia Eagles","Eagles","Philadelphia",None),
  ("PIT","Pittsburgh Steelers","Steelers","Pittsburgh",None),("SF","San Francisco 49ers","49ers","San Francisco–Oakland–San Jose",None),
  ("SEA","Seattle Seahawks","Seahawks","Seattle–Tacoma",None),("TB","Tampa Bay Buccaneers","Buccaneers","Tampa–St. Petersburg",None),
  ("TEN","Tennessee Titans","Titans","Nashville",None),("WAS","Washington Commanders","Commanders","Washington, D.C.",None)],
 "NBA": [
  ("ATL","Atlanta Hawks","Hawks","Atlanta",None),("BOS","Boston Celtics","Celtics","Boston",None),
  ("BKN","Brooklyn Nets","Nets","New York",None),("CHA","Charlotte Hornets","Hornets","Charlotte",None),
  ("CHI","Chicago Bulls","Bulls","Chicago",None),("CLE","Cleveland Cavaliers","Cavaliers","Cleveland–Akron",None),
  ("DAL","Dallas Mavericks","Mavericks","Dallas–Fort Worth",None),("DEN","Denver Nuggets","Nuggets","Denver",None),
  ("DET","Detroit Pistons","Pistons","Detroit",None),("GSW","Golden State Warriors","Warriors","San Francisco–Oakland–San Jose",None),
  ("HOU","Houston Rockets","Rockets","Houston",None),("IND","Indiana Pacers","Pacers","Indianapolis",None),
  ("LAC","LA Clippers","Clippers","Los Angeles",None),("LAL","Los Angeles Lakers","Lakers","Los Angeles",None),
  ("MEM","Memphis Grizzlies","Grizzlies","Memphis",None),("MIA","Miami Heat","Heat","Miami–Fort Lauderdale",None),
  ("MIL","Milwaukee Bucks","Bucks","Milwaukee",None),("MIN","Minnesota Timberwolves","Timberwolves","Minneapolis–St. Paul",None),
  ("NOP","New Orleans Pelicans","Pelicans","New Orleans",None),("NYK","New York Knicks","Knicks","New York",None),
  ("OKC","Oklahoma City Thunder","Thunder","Oklahoma City",None),("ORL","Orlando Magic","Magic","Orlando–Daytona Beach–Melbourne",None),
  ("PHI","Philadelphia 76ers","76ers","Philadelphia",None),("PHX","Phoenix Suns","Suns","Phoenix",None),
  ("POR","Portland Trail Blazers","Trail Blazers","Portland, OR",None),("SAC","Sacramento Kings","Kings","Sacramento–Stockton–Modesto",None),
  ("SAS","San Antonio Spurs","Spurs","San Antonio",None),("UTA","Utah Jazz","Jazz","Salt Lake City",None),
  ("WAS","Washington Wizards","Wizards","Washington, D.C.",None)],
 "NHL": [
  ("ANA","Anaheim Ducks","Ducks","Los Angeles",None),("BOS","Boston Bruins","Bruins","Boston",None),
  ("BUF","Buffalo Sabres","Sabres","Buffalo",None),("CAR","Carolina Hurricanes","Hurricanes","Raleigh–Durham",None),
  ("CHI","Chicago Blackhawks","Blackhawks","Chicago",None),("COL","Colorado Avalanche","Avalanche","Denver",None),
  ("CBJ","Columbus Blue Jackets","Blue Jackets","Columbus, OH",None),("DAL","Dallas Stars","Stars","Dallas–Fort Worth",None),
  ("DET","Detroit Red Wings","Red Wings","Detroit",None),("FLA","Florida Panthers","Panthers","Miami–Fort Lauderdale",None),
  ("LAK","Los Angeles Kings","Kings","Los Angeles",None),("MIN","Minnesota Wild","Wild","Minneapolis–St. Paul",None),
  ("NSH","Nashville Predators","Predators","Nashville",None),("NJD","New Jersey Devils","Devils","New York",None),
  ("NYI","New York Islanders","Islanders","New York",None),("NYR","New York Rangers","Rangers","New York",None),
  ("PHI","Philadelphia Flyers","Flyers","Philadelphia",None),("PIT","Pittsburgh Penguins","Penguins","Pittsburgh",None),
  ("SJS","San Jose Sharks","Sharks","San Francisco–Oakland–San Jose",None),("SEA","Seattle Kraken","Kraken","Seattle–Tacoma",None),
  ("STL","St. Louis Blues","Blues","St. Louis",None),("TBL","Tampa Bay Lightning","Lightning","Tampa–St. Petersburg",None),
  ("UTA","Utah Mammoth","Mammoth","Salt Lake City","renamed from Utah Hockey Club in 2025"),
  ("VGK","Vegas Golden Knights","Golden Knights","Las Vegas",None),("WSH","Washington Capitals","Capitals","Washington, D.C.",None)],
 "MLB": [
  ("ARI","Arizona Diamondbacks","Diamondbacks","Phoenix",None),("ATL","Atlanta Braves","Braves","Atlanta",None),
  ("BAL","Baltimore Orioles","Orioles","Baltimore",None),("BOS","Boston Red Sox","Red Sox","Boston",None),
  ("CHC","Chicago Cubs","Cubs","Chicago",None),("CWS","Chicago White Sox","White Sox","Chicago",None),
  ("CIN","Cincinnati Reds","Reds","Cincinnati",None),("CLE","Cleveland Guardians","Guardians","Cleveland–Akron",None),
  ("COL","Colorado Rockies","Rockies","Denver",None),("DET","Detroit Tigers","Tigers","Detroit",None),
  ("HOU","Houston Astros","Astros","Houston",None),("KC","Kansas City Royals","Royals","Kansas City",None),
  ("LAA","Los Angeles Angels","Angels","Los Angeles",None),("LAD","Los Angeles Dodgers","Dodgers","Los Angeles",None),
  ("MIA","Miami Marlins","Marlins","Miami–Fort Lauderdale",None),("MIL","Milwaukee Brewers","Brewers","Milwaukee",None),
  ("MIN","Minnesota Twins","Twins","Minneapolis–St. Paul",None),("NYM","New York Mets","Mets","New York",None),
  ("NYY","New York Yankees","Yankees","New York",None),
  ("ATH","Athletics","Athletics","Sacramento–Stockton–Modesto","plays in West Sacramento 2025-2027 before the planned move to Las Vegas; TV home market may still be listed as San Francisco"),
  ("PHI","Philadelphia Phillies","Phillies","Philadelphia",None),("PIT","Pittsburgh Pirates","Pirates","Pittsburgh",None),
  ("SD","San Diego Padres","Padres","San Diego",None),("SF","San Francisco Giants","Giants","San Francisco–Oakland–San Jose",None),
  ("SEA","Seattle Mariners","Mariners","Seattle–Tacoma",None),("STL","St. Louis Cardinals","Cardinals","St. Louis",None),
  ("TB","Tampa Bay Rays","Rays","Tampa–St. Petersburg","home ballpark changed after the 2024 hurricane damage to Tropicana Field"),
  ("TEX","Texas Rangers","Rangers","Dallas–Fort Worth",None),("WSH","Washington Nationals","Nationals","Washington, D.C.",None)],
 "MLS": [
  ("ATL","Atlanta United","Atlanta United","Atlanta",None),("ATX","Austin FC","Austin FC","Austin",None),
  ("CLT","Charlotte FC","Charlotte FC","Charlotte",None),("CHI","Chicago Fire FC","Chicago Fire","Chicago",None),
  ("CIN","FC Cincinnati","FC Cincinnati","Cincinnati",None),("COL","Colorado Rapids","Rapids","Denver",None),
  ("CLB","Columbus Crew","Columbus Crew","Columbus, OH",None),("DC","D.C. United","D.C. United","Washington, D.C.",None),
  ("DAL","FC Dallas","FC Dallas","Dallas–Fort Worth",None),("HOU","Houston Dynamo FC","Dynamo","Houston",None),
  ("SKC","Sporting Kansas City","Sporting KC","Kansas City",None),("LA","LA Galaxy","LA Galaxy","Los Angeles",None),
  ("LAFC","Los Angeles FC","LAFC","Los Angeles",None),("MIA","Inter Miami CF","Inter Miami","Miami–Fort Lauderdale",None),
  ("MIN","Minnesota United FC","Minnesota United","Minneapolis–St. Paul",None),("NSH","Nashville SC","Nashville SC","Nashville",None),
  ("NE","New England Revolution","Revolution","Boston",None),("NYC","New York City FC","NYCFC","New York",None),
  ("RBNY","New York Red Bulls","Red Bulls","New York",None),("ORL","Orlando City SC","Orlando City","Orlando–Daytona Beach–Melbourne",None),
  ("PHI","Philadelphia Union","Philadelphia Union","Philadelphia",None),("POR","Portland Timbers","Timbers","Portland, OR",None),
  ("RSL","Real Salt Lake","Real Salt Lake","Salt Lake City",None),("SD","San Diego FC","San Diego FC","San Diego",None),
  ("SJ","San Jose Earthquakes","Earthquakes","San Francisco–Oakland–San Jose",None),("SEA","Seattle Sounders FC","Sounders","Seattle–Tacoma",None),
  ("STL","St. Louis City SC","St. Louis City","St. Louis",None)],
}

dmas = json.load(open(sys.argv[1]))
by_name = {m["name"]: m["code"] for m in dmas["markets"]}
leagues = []
for lg, teams in T.items():
    out = []
    for abbr, full, nick, mkt, note in teams:
        if mkt not in by_name: sys.exit(f"unknown market {mkt!r} for {full}")
        t = {"id": abbr, "name": full, "short": nick, "dma": by_name[mkt]}
        if note: t["verify"] = True; t["note"] = note
        out.append(t)
    leagues.append({"id": lg, "teams": out})
doc = {"schema": "ipvio.locals.leagues/1", "version": 1, "updated": datetime.date.today().isoformat(),
       "notes": "US teams only (Canadian teams left out); dma = Nielsen DMA code of the home market; verify = check before relying on it",
       "leagues": leagues}
lines = ['{', f'  "schema": "{doc["schema"]}",', f'  "version": 1,', f'  "updated": "{doc["updated"]}",', f'  "notes": {json.dumps(doc["notes"])},', '  "leagues": [']
for i, l in enumerate(leagues):
    lines.append('    {"id": "%s", "teams": [' % l["id"])
    lines += ['      ' + json.dumps(t, ensure_ascii=False) + (',' if j < len(l["teams"]) - 1 else '') for j, t in enumerate(l["teams"])]
    lines.append('    ]}' + (',' if i < len(leagues) - 1 else ''))
lines += ['  ]', '}']
open(sys.argv[2], "w").write("\n".join(lines) + "\n")
json.load(open(sys.argv[2]))
print({l["id"]: len(l["teams"]) for l in leagues}, "verify:", sum(1 for l in leagues for t in l["teams"] if t.get("verify")))
