const ESC: u8 = 0x1b;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum State {
    Ground,
    Esc,
    EscUnderscore,
    Graphics,
    GraphicsEsc,
}

/// Removes kitty graphics commands (`ESC _ G ... ESC \`) from a byte stream. Commands can span
/// several chunks.
pub struct GraphicsFilter {
    state: State,
}

impl GraphicsFilter {
    pub fn new() -> Self {
        Self { state: State::Ground }
    }

    pub fn push(&mut self, input: &[u8], out: &mut Vec<u8>) {
        for &b in input {
            self.state = match (self.state, b) {
                (State::Ground, ESC) => State::Esc,
                (State::Ground, _) => {
                    out.push(b);
                    State::Ground
                }
                (State::Esc, b'_') => State::EscUnderscore,
                (State::Esc, ESC) => {
                    out.push(ESC);
                    State::Esc
                }
                (State::Esc, _) => {
                    out.extend_from_slice(&[ESC, b]);
                    State::Ground
                }
                (State::EscUnderscore, b'G') => State::Graphics,
                (State::EscUnderscore, ESC) => {
                    out.extend_from_slice(&[ESC, b'_']);
                    State::Esc
                }
                (State::EscUnderscore, _) => {
                    out.extend_from_slice(&[ESC, b'_', b]);
                    State::Ground
                }
                (State::Graphics, ESC) => State::GraphicsEsc,
                (State::Graphics, _) => State::Graphics,
                (State::GraphicsEsc, b'\\') => State::Ground,
                (State::GraphicsEsc, ESC) => State::GraphicsEsc,
                (State::GraphicsEsc, _) => State::Graphics,
            };
        }
    }

    /// True when the last bytes are held back because they may start a graphics command.
    pub fn holding(&self) -> bool {
        matches!(self.state, State::Esc | State::EscUnderscore)
    }

    /// Writes held-back bytes, for when no more input arrives to decide them.
    pub fn flush(&mut self, out: &mut Vec<u8>) {
        match self.state {
            State::Esc => out.push(ESC),
            State::EscUnderscore => out.extend_from_slice(&[ESC, b'_']),
            _ => {}
        }
        if matches!(self.state, State::Esc | State::EscUnderscore) {
            self.state = State::Ground;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(chunks: &[&[u8]]) -> Vec<u8> {
        let mut f = GraphicsFilter::new();
        let mut out = Vec::new();
        for c in chunks {
            f.push(c, &mut out);
        }
        f.flush(&mut out);
        out
    }

    #[test]
    fn passes_plain_text_and_other_sequences() {
        let input = b"a\x1b[31mb\x1b]0;t\x1b\\c\x1b_Xapc\x1b\\d";
        assert_eq!(run(&[input]), input.to_vec());
    }

    #[test]
    fn drops_graphics_command() {
        assert_eq!(run(&[b"a\x1b_Ga=T,f=100;AAAA\x1b\\b"]), b"ab".to_vec());
    }

    #[test]
    fn drops_graphics_split_over_chunks() {
        let chunks: [&[u8]; 6] = [b"a\x1b", b"_", b"Gm=1;AA", b"AA\x1b", b"\\", b"b"];
        assert_eq!(run(&chunks), b"ab".to_vec());
    }

    #[test]
    fn drops_consecutive_graphics_commands() {
        let input = b"\x1b_Gm=1;AA\x1b\\\x1b_Gm=0;BB\x1b\\x";
        assert_eq!(run(&[input]), b"x".to_vec());
    }

    #[test]
    fn keeps_escape_inside_graphics_from_ending_it_early() {
        assert_eq!(run(&[b"\x1b_Gx\x1bAy\x1b\\z"]), b"z".to_vec());
    }

    #[test]
    fn keeps_double_escape() {
        assert_eq!(run(&[b"\x1b\x1b[Aq"]), b"\x1b\x1b[Aq".to_vec());
    }

    #[test]
    fn flush_releases_held_escape() {
        let mut f = GraphicsFilter::new();
        let mut out = Vec::new();
        f.push(b"a\x1b", &mut out);
        assert_eq!(out, b"a".to_vec());
        f.flush(&mut out);
        assert_eq!(out, b"a\x1b".to_vec());
        f.push(b"[A", &mut out);
        assert_eq!(out, b"a\x1b[A".to_vec());
    }
}
