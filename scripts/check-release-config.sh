#!/usr/bin/env bash
#
# Static checks for the release configuration: the release workflow (triggers, permissions,
# tag-only guards, publish steps, artifact retention, secrets), Dependabot ecosystems, the
# release-notes categories and the compose file. Also runs check-action-pins.sh on the workflow
# and actionlint when it is installed.
#
# Usage:
#   scripts/check-release-config.sh [WORKFLOW]   # default: .github/workflows/release.yml
#
# Exit codes: 0 PASS; 1 FAIL (each problem printed as FAIL: <message>); 2 setup error
# (ruby missing or an input file missing).

set -u -o pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
wf=${1:-$ROOT/.github/workflows/release.yml}
dep=$ROOT/.github/dependabot.yml
rel=$ROOT/.github/release.yml
compose=$ROOT/docker-compose.yml

if ! command -v ruby >/dev/null 2>&1; then
  echo "check-release-config: ruby not found" >&2
  exit 2
fi
for f in "$wf" "$dep" "$rel" "$compose"; do
  if [ ! -f "$f" ]; then
    echo "check-release-config: input file not found: $f" >&2
    exit 2
  fi
done

fails=0

report=$(ruby -ryaml - "$wf" "$dep" "$rel" "$compose" <<'RUBY'
TAG = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
DISPATCH = "github.event_name == 'workflow_dispatch'"
CLUSTER = "n1=n1:7000,n2=n2:7000,n3=n3:7000"
JOB_IDS = %w[build image-dry-run publish-image publish-release].freeze
PUBLISH_IDS = %w[publish-image publish-release].freeze
READ_ONLY = { "contents" => "read" }.freeze

wf_path, dep_path, rel_path, compose_path = ARGV
problems = []

def load_yaml(path, problems)
  YAML.safe_load(File.read(path), aliases: true)
rescue StandardError => e
  problems << "cannot parse #{path}: #{e.message.lines.first.to_s.strip}"
  nil
end

def hash_of(value)
  value.is_a?(Hash) ? value : {}
end

def steps_of(job)
  steps = job["steps"]
  steps.is_a?(Array) ? steps.select { |s| s.is_a?(Hash) } : []
end

wf = load_yaml(wf_path, problems)
dep = load_yaml(dep_path, problems)
rel = load_yaml(rel_path, problems)
compose = load_yaml(compose_path, problems)

if wf.is_a?(Hash)
  on = hash_of(wf["on"] || wf[true])
  unless on.keys.sort == %w[push workflow_dispatch] && on["push"] == { "tags" => ["v*"] }
    problems << "W1: triggers must be exactly push (tags: [v*]) and workflow_dispatch"
  end
  problems << "W2: top-level permissions must be {contents: read}" unless wf["permissions"] == READ_ONLY
  unless hash_of(wf["concurrency"])["cancel-in-progress"] == false
    problems << "W3: concurrency.cancel-in-progress must be false"
  end

  raw_jobs = hash_of(wf["jobs"])
  jobs = raw_jobs.transform_values { |j| hash_of(j) }
  if raw_jobs.keys.sort != JOB_IDS.sort
    problems << "W4: job ids must be exactly #{JOB_IDS.join(', ')}; found #{raw_jobs.keys.join(', ')}"
  end
  jobs.each do |id, job|
    problems << "W4: #{id} needs an Integer timeout-minutes" unless job["timeout-minutes"].is_a?(Integer)
    problems << "W4: #{id} needs an explicit permissions Hash" unless job["permissions"].is_a?(Hash)
  end

  build = jobs["build"] || {}
  problems << "W5: build must have no if" if build.key?("if")
  problems << "W5: build permissions must be {contents: read}" unless build["permissions"] == READ_ONLY
  dry = jobs["image-dry-run"] || {}
  problems << "W5: image-dry-run if must be the workflow_dispatch condition" unless dry["if"] == DISPATCH
  problems << "W5: image-dry-run permissions must be {contents: read}" unless dry["permissions"] == READ_ONLY
  pimg = jobs["publish-image"] || {}
  problems << "W5: publish-image if must be the tag-push condition" unless pimg["if"] == TAG
  unless pimg["permissions"] == { "contents" => "read", "packages" => "write" }
    problems << "W5: publish-image permissions must be {contents: read, packages: write}"
  end
  prel = jobs["publish-release"] || {}
  problems << "W5: publish-release if must be the tag-push condition" unless prel["if"] == TAG
  unless prel["permissions"] == { "contents" => "write" }
    problems << "W5: publish-release permissions must be {contents: write}"
  end
  needs = Array(prel["needs"])
  unless needs.include?("build") && needs.include?("publish-image")
    problems << "W5: publish-release needs must include build and publish-image"
  end

  jobs.each do |id, job|
    next if PUBLISH_IDS.include?(id)
    problems << "W6: #{id} must not have write permissions" if hash_of(job["permissions"]).values.include?("write")
  end

  PUBLISH_IDS.each do |id|
    first = steps_of(jobs[id] || {}).first || {}
    run = first["run"].to_s
    unless first["name"] == "Guard (tag push only)" && run.include?("refs/tags/v*") && run.include?("exit 1")
      problems << "W7: #{id} step 0 must be the tag-only guard"
    end
  end

  %w[build image-dry-run].each do |id|
    steps_of(jobs[id] || {}).each do |s|
      run = s["run"]
      uses = s["uses"]
      if run.is_a?(String) && run.match?(%r{docker\s+(push|login)|gh\s+release|--push\b})
        problems << "W8: #{id} step '#{s['name']}' pushes, logs in or releases"
      end
      if uses.is_a?(String) && uses.match?(%r{docker/(login|build-push)-action|release}i)
        problems << "W8: #{id} step '#{s['name']}' uses a push or release action"
      end
    end
  end

  rel_run = steps_of(jobs["publish-release"] || {}).map { |s| s["run"].to_s }.join("\n")
  %w[--generate-notes --prerelease SHA256SUMS .zip .tar].each do |t|
    problems << "W9: publish-release run must include #{t}" unless rel_run.include?(t)
  end
  img_run = steps_of(jobs["publish-image"] || {}).map { |s| s["run"].to_s }.join("\n")
  ["ghcr.io/", "docker push", ":latest", '"$PRERELEASE" != true'].each do |t|
    problems << "W9: publish-image run must include #{t}" unless img_run.include?(t)
  end

  jobs.each do |id, job|
    steps_of(job).each do |s|
      next unless s["uses"].is_a?(String) && s["uses"].start_with?("actions/upload-artifact@")
      days = hash_of(s["with"])["retention-days"]
      unless days.is_a?(Integer) && (1..14).cover?(days)
        problems << "W10: #{id} upload-artifact retention-days must be an Integer in 1..14"
      end
    end
  end

  File.read(wf_path).scan(/secrets\.([A-Za-z_]+)/).flatten.uniq.each do |name|
    problems << "W11: secrets.#{name} is not allowed; only GITHUB_TOKEN" unless name == "GITHUB_TOKEN"
  end
end

if dep.is_a?(Hash)
  updates = dep["updates"].is_a?(Array) ? dep["updates"].select { |u| u.is_a?(Hash) } : []
  ecos = updates.map { |u| u["package-ecosystem"] }
  %w[gradle github-actions npm docker].each do |e|
    problems << "D1: dependabot has no #{e} ecosystem" unless ecos.include?(e)
  end
  updates.each do |u|
    eco = u["package-ecosystem"]
    problems << "D1: #{eco} schedule.interval must be weekly" unless hash_of(u["schedule"])["interval"] == "weekly"
    groups = u["groups"]
    problems << "D1: #{eco} needs a non-empty groups Hash" unless groups.is_a?(Hash) && !groups.empty?
  end
end

if rel.is_a?(Hash)
  cats = hash_of(rel["changelog"])["categories"]
  if !cats.is_a?(Array) || cats.empty?
    problems << "N1: release.yml changelog.categories must be a non-empty list"
  else
    cats.each do |c|
      ok = c.is_a?(Hash) && c["title"].is_a?(String) && c["labels"].is_a?(Array) && !c["labels"].empty?
      problems << "N1: each changelog category needs a title and non-empty labels" unless ok
    end
  end
end

if compose.is_a?(Hash)
  services = hash_of(compose["services"])
  %w[n1 n2 n3 kv].each do |s|
    problems << "C1: compose service #{s} is missing" unless services.key?(s)
  end
  %w[n1 n2 n3].each do |n|
    svc = hash_of(services[n])
    expected_cmd = ["node", "--id", n, "--cluster", CLUSTER, "--data-dir", "/data"]
    problems << "C1: #{n} command must be #{expected_cmd.join(' ')}" unless svc["command"] == expected_cmd
    problems << "C1: #{n} volumes must be [#{n}-data:/data]" unless svc["volumes"] == ["#{n}-data:/data"]
    problems << "C1: #{n} must have build" unless svc.key?("build")
    problems << "C1: #{n} restart must be \"no\"" unless svc["restart"] == "no"
  end
  kv = hash_of(services["kv"])
  problems << "C1: kv profiles must be [client]" unless kv["profiles"] == ["client"]
  problems << "C1: kv must not have depends_on" if kv.key?("depends_on")
  entry = Array(kv["entrypoint"])
  unless entry.include?("kv") && entry.include?(CLUSTER)
    problems << "C1: kv entrypoint must include kv and the cluster string"
  end
  volumes = hash_of(compose["volumes"])
  %w[n1-data n2-data n3-data].each do |v|
    problems << "C1: top-level volume #{v} is missing" unless volumes.key?(v)
  end
  problems << "C1: top-level secrets are not allowed" if compose.key?("secrets")
  services.each do |name, svc|
    next unless svc.is_a?(Hash) && svc["volumes"].is_a?(Array)
    svc["volumes"].each do |v|
      next unless v.is_a?(String)
      source = v.split(":", 2).first.to_s
      problems << "C1: #{name} volume source must not be a host path: #{v}" if source.start_with?("/", ".")
    end
  end
end

problems.each { |p| puts "FAIL: #{p}" }
RUBY
)
rb_rc=$?

if [ -n "$report" ]; then
  printf '%s\n' "$report"
fi
fails=$(grep -c '^FAIL:' <<<"$report")
if [ "$rb_rc" -ne 0 ]; then
  echo "FAIL: release config checker failed to run (ruby exit $rb_rc)"
  fails=$((fails + 1))
fi

if ! "$ROOT/scripts/check-action-pins.sh" "$wf"; then
  echo "FAIL: action pins in $wf (scripts/check-action-pins.sh)"
  fails=$((fails + 1))
fi

if command -v actionlint >/dev/null 2>&1; then
  if ! actionlint "$wf"; then
    echo "FAIL: actionlint reported problems in $wf"
    fails=$((fails + 1))
  fi
else
  echo "actionlint not found; skipped (manual review)"
fi

if [ "$fails" -eq 0 ]; then
  echo "PASS: release config OK"
  exit 0
fi
echo "FAIL: $fails problem(s)"
exit 1
